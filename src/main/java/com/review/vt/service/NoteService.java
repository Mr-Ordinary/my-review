package com.review.vt.service;

import com.review.vt.domain.Note;
import com.review.vt.domain.Question;
import com.review.vt.repo.NoteRepository;
import com.review.vt.repo.QuestionRepository;
import com.review.vt.web.Dtos.NoteDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 练习笔记：per-practice 的结构化条目（答题时 / 看批改时 / 笔记页手动）。
 *
 * 两个纯函数（normalizeText / requireScene）把「什么算合法笔记」卡死在数据层，
 * 不指望前端的 maxlength——前端只防误操作，服务端才是底线。
 */
@Service
public class NoteService {

    /** 合法场景白名单 */
    static final Set<String> SCENES = Set.of("answering", "grading", "manual");

    private final NoteRepository notes;
    private final QuestionRepository questions;

    public NoteService(NoteRepository notes, QuestionRepository questions) {
        this.notes = notes;
        this.questions = questions;
    }

    /* ================= 纯逻辑（可单测） ================= */

    /** 笔记正文清洗：trim、拒绝空、拒绝超长。非法直接抛 IllegalArgumentException → 400 */
    public static String normalizeText(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("笔记内容不能为空");
        if (s.length() > Limits.MAX_NOTE_TEXT) {
            throw new IllegalArgumentException("笔记最长 " + Limits.MAX_NOTE_TEXT + " 字");
        }
        return s;
    }

    /** 场景白名单：空/blank 归 manual，非法值拒绝 */
    public static String requireScene(String scene) {
        if (scene == null || scene.isBlank()) return "manual";
        String s = scene.trim();
        if (!SCENES.contains(s)) throw new IllegalArgumentException("未知的笔记场景：" + s);
        return s;
    }

    /* ================= 读写 ================= */

    public NoteDto create(String practiceId, String questionId, String scene, String text) {
        String clean = normalizeText(text);
        String sc = requireScene(scene);
        String qid = questionId == null || questionId.isBlank() ? null : questionId.trim();
        if (qid != null) {
            Question q = questions.findById(qid)
                    .orElseThrow(() -> new NotFoundException("题目不存在或不属于这个练习"));
            if (!practiceId.equals(q.getPracticeId())) {
                throw new NotFoundException("题目不存在或不属于这个练习");
            }
        }
        if (notes.findByPracticeIdOrderByCreatedAtDesc(practiceId).size() >= Limits.MAX_NOTES_PER_PRACTICE) {
            throw new IllegalArgumentException("一个练习最多 " + Limits.MAX_NOTES_PER_PRACTICE + " 条笔记");
        }
        long now = System.currentTimeMillis();
        Note n = new Note();
        n.setId(UUID.randomUUID().toString());
        n.setPracticeId(practiceId);
        n.setQuestionId(qid);
        n.setScene(sc);
        n.setText(clean);
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        return dto(notes.save(n));
    }

    public NoteDto update(String practiceId, String noteId, String text) {
        Note n = requireOwned(practiceId, noteId);
        n.setText(normalizeText(text));
        n.setUpdatedAt(System.currentTimeMillis());
        return dto(notes.save(n));
    }

    public void delete(String practiceId, String noteId) {
        notes.deleteAll(List.of(requireOwned(practiceId, noteId)));
    }

    public List<NoteDto> dtoList(String practiceId) {
        List<NoteDto> out = new ArrayList<>();
        for (Note n : notes.findByPracticeIdOrderByCreatedAtDesc(practiceId)) out.add(dto(n));
        return out;
    }

    public long countByPractice(String practiceId) {
        return notes.findByPracticeIdOrderByCreatedAtDesc(practiceId).size();
    }

    /** 级联删除用（删练习/删阶段） */
    public void deleteByPractice(String practiceId) {
        notes.deleteAll(notes.findByPracticeIdOrderByCreatedAtDesc(practiceId));
    }

    private Note requireOwned(String practiceId, String noteId) {
        Note n = notes.findById(noteId).orElseThrow(() -> new NotFoundException("笔记不存在"));
        if (!practiceId.equals(n.getPracticeId())) throw new NotFoundException("笔记不存在");
        return n;
    }

    private static NoteDto dto(Note n) {
        return new NoteDto(n.getId(), n.getPracticeId(), n.getQuestionId(), n.getScene(),
                n.getText(), n.getCreatedAt(), n.getUpdatedAt());
    }
}
