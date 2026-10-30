package com.review.vt.service;

import com.review.vt.domain.Attempt;
import com.review.vt.domain.FollowUp;
import com.review.vt.domain.Paper;
import com.review.vt.domain.Question;
import com.review.vt.repo.AttemptRepository;
import com.review.vt.repo.FollowUpRepository;
import com.review.vt.repo.PaperRepository;
import com.review.vt.repo.QuestionRepository;
import com.review.vt.web.Dtos.FollowUpDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 追问：**按题隔离**的聊天记录 + AI 答疑。
 *
 * <p>关键约束：
 * <ul>
 *   <li>只把这一道题的信息给 AI（题面/作答/批改/参考答案/问点/考察点），
 *       不带其他题、不带练习记忆——问答不会串题</li>
 *   <li>交卷批改后才能追问（追问要用到批改与参考答案）</li>
 *   <li>历史只带最近几轮且总长有顶，问再多也不会撑爆上下文</li>
 *   <li>AI 失败时不落库，用户重试即可，不会留下半截对话</li>
 * </ul>
 */
@Service
public class FollowUpService {

    /** 最近带几轮对话进上下文（10 条 = 5 轮） */
    static final int HISTORY_MESSAGES = 10;
    /** 历史对话总字数硬顶 */
    static final int HISTORY_CHARS = 3000;
    /** 单条历史消息截断长度 */
    static final int HISTORY_MESSAGE_CHARS = 500;
    /** 追问回答的输出额度（不需要太长，够口述即可） */
    static final int ANSWER_TOKENS = 2048;

    private final FollowUpRepository followUps;
    private final QuestionRepository questions;
    private final AttemptRepository attempts;
    private final PaperRepository papers;
    private final LlmService llm;

    public FollowUpService(FollowUpRepository followUps, QuestionRepository questions,
                           AttemptRepository attempts, PaperRepository papers, LlmService llm) {
        this.followUps = followUps;
        this.questions = questions;
        this.attempts = attempts;
        this.papers = papers;
        this.llm = llm;
    }

    /** 这道题的聊天记录（按时间升序） */
    public List<FollowUpDto> list(String questionId) {
        getQuestion(questionId);
        return dtoList(questionId);
    }

    /** 提一个问题，返回更新后的完整聊天记录（含用户提问与 AI 回答） */
    public List<FollowUpDto> ask(String questionId, String content) {
        Question q = getQuestion(questionId);
        Paper paper = papers.findById(q.getPaperId())
                .orElseThrow(() -> new NotFoundException("题目所属的卷不存在或已删除"));
        if (!"graded".equals(paper.getStatus())) {
            throw new IllegalArgumentException("交卷批改后才能追问——追问会带上批改与参考答案");
        }
        // 只有「卷已批改」是门槛——没作答的题也有题面与参考答案，同样可以问
        Attempt a = latestAttempt(questionId);
        String text = content == null ? "" : content.trim();
        if (text.isBlank()) throw new IllegalArgumentException("问题不能为空");
        if (text.length() > Limits.MAX_FOLLOWUP_TEXT) {
            throw new IllegalArgumentException("问题太长了（上限 " + Limits.MAX_FOLLOWUP_TEXT + " 字）");
        }
        long count = followUps.countByQuestionId(questionId);
        if (count + 2 > Limits.MAX_FOLLOWUP_MESSAGES) {
            throw new IllegalArgumentException("这道题的追问已达上限（"
                    + (Limits.MAX_FOLLOWUP_MESSAGES / 2) + " 轮），先清空记录再继续");
        }

        // 先调 AI 再落库：失败时不留半截对话，用户直接重试
        List<FollowUp> history = followUps.findByQuestionIdOrderByCreatedAtAsc(questionId);
        String reply = llm.chatText(PromptTemplates.FOLLOWUP_SYSTEM,
                PromptTemplates.followUpUser(buildContext(q, a), fitHistory(history), text),
                ANSWER_TOKENS, false);
        reply = reply == null ? "" : reply.trim();
        if (reply.isBlank()) throw new LlmService.LlmException("AI 没有返回内容，请重试");
        reply = MemoryRules.cut(reply, Limits.MAX_FOLLOWUP_TEXT);

        long now = System.currentTimeMillis();
        save(q, "user", text, now);
        save(q, "assistant", reply, now + 1); // +1 保证顺序稳定
        return dtoList(questionId);
    }

    /** 清空这道题的聊天记录（不动题目与作答） */
    public void clear(String questionId) {
        getQuestion(questionId);
        followUps.deleteAll(followUps.findByQuestionIdOrderByCreatedAtAsc(questionId));
    }

    /**
     * 只组装这一道题的上下文——这是「问答不串题」的落点。
     * 题面/作答/批改/参考答案/题面问点/考察点，各段都有硬上限。
     */
    static String buildContext(Question q, Attempt a) {
        StringBuilder sb = new StringBuilder();
        sb.append("【题面】\n").append(MemoryRules.cut(q.getPrompt(), 800)).append('\n');
        if (a != null) {
            sb.append("\n【我的作答】\n").append(MemoryRules.cut(a.getAnswer(), 1200)).append('\n');
            sb.append("\n【批改】\n");
            if (a.getScore() != null) {
                sb.append("表现分 ").append(a.getScore()).append("/10；实得 ")
                  .append(a.getPoints() == null ? "—" : a.getPoints())
                  .append(" 分（本题满分 ").append(q.getPoints()).append("）\n");
            } else {
                sb.append("尚未批改\n");
            }
            if (a.getFeedback() != null && !a.getFeedback().isBlank()) {
                sb.append(MemoryRules.cut(a.getFeedback(), 1200)).append('\n');
            }
        } else {
            // 没作答也能问：用户通常是「这题我空着，想搞懂标准答案」
            sb.append("\n【我的作答】\n（未作答）\n");
            sb.append("\n【批改】\n未作答，本题按 0 分计。"
                    + "用户可能想问「这题应该怎么答」「参考答案为什么这样」，请围绕题面讲解。\n");
        }
        sb.append("\n【参考答案】\n").append(MemoryRules.cut(q.getReferenceAnswer(), 800)).append('\n');

        List<String> asks = PracticeService.extractAsks(q.getPrompt());
        if (!asks.isEmpty()) {
            sb.append("\n【题面问点】\n");
            for (int i = 0; i < asks.size(); i++) {
                sb.append(i + 1).append(". ").append(asks.get(i)).append('\n');
            }
        }
        List<String> points = Mappings.parseStringList(q.getKnowledgePointsJson());
        if (!points.isEmpty()) {
            sb.append("\n【考察点】\n").append(String.join("、", points)).append('\n');
        }
        return sb.toString();
    }

    /** 只带最近几轮，且总长有顶——追问再多也不会把上下文撑爆 */
    static String fitHistory(List<FollowUp> history) {
        if (history == null || history.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0 && lines.size() < HISTORY_MESSAGES; i--) {
            FollowUp f = history.get(i);
            String line = ("user".equals(f.getRole()) ? "用户：" : "教练：")
                    + MemoryRules.cut(f.getContent(), HISTORY_MESSAGE_CHARS);
            if (used + line.length() > HISTORY_CHARS) break;
            lines.add(line);
            used += line.length();
        }
        Collections.reverse(lines);
        return String.join("\n", lines);
    }

    private List<FollowUpDto> dtoList(String questionId) {
        List<FollowUpDto> out = new ArrayList<>();
        for (FollowUp f : followUps.findByQuestionIdOrderByCreatedAtAsc(questionId)) {
            out.add(new FollowUpDto(f.getId(), f.getRole(), f.getContent(), f.getCreatedAt()));
        }
        return out;
    }

    private Question getQuestion(String questionId) {
        return questions.findById(questionId)
                .orElseThrow(() -> new NotFoundException("题目不存在或已删除"));
    }

    private Attempt latestAttempt(String questionId) {
        List<Attempt> list = attempts.findByQuestionIdOrderBySequenceAsc(questionId);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    private void save(Question q, String role, String content, long at) {
        FollowUp f = new FollowUp();
        f.setId(UUID.randomUUID().toString());
        f.setPracticeId(q.getPracticeId());
        f.setQuestionId(q.getId());
        f.setRole(role);
        f.setContent(content);
        f.setCreatedAt(at);
        followUps.save(f);
    }
}
