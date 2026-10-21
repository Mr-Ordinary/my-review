package com.review.vt.service;

import com.review.vt.domain.Note;
import com.review.vt.domain.Practice;
import com.review.vt.repo.NoteRepository;
import com.review.vt.repo.PracticeRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 练习记事本：**一整块可编辑文本**，随手记、随手改。
 *
 * 刻意不做「一条一条」的记录——条目模型没法自由编辑（在中间改一段算哪条的修改时间？），
 * 而用户要的就是「一个大的记事本，可以随时修改」。
 *
 * 三个动作：
 * - {@link #text} 取全文（顺带把旧的条目数据迁进来）
 * - {@link #setText} 整篇覆盖（前端编辑后保存）
 * - {@link #append} 末尾换行追加（答题时点「记笔记」）
 *
 * 追加只加文本、**不掺题号**——用户明确不要对应关系，看时间悬停即可。
 */
@Service
public class NoteService {

    private final NoteRepository notes;
    private final PracticeRepository practices;

    public NoteService(NoteRepository notes, PracticeRepository practices) {
        this.notes = notes;
        this.practices = practices;
    }

    /* ================= 纯逻辑（可单测） ================= */

    /** 正文清洗：空内容对「覆盖」是合法的（等于清空），只挡超长 */
    public static String normalizeText(String text) {
        String s = text == null ? "" : text;
        if (s.length() > Limits.MAX_NOTE_TEXT) {
            throw new IllegalArgumentException("记事本最长 " + Limits.MAX_NOTE_TEXT + " 字");
        }
        return s;
    }

    /** 追加：末尾换行 + 新内容。空追加直接忽略，别在末尾堆空行 */
    public static String appendTo(String current, String add) {
        String base = current == null ? "" : current;
        String s = add == null ? "" : add.trim();
        if (s.isEmpty()) return base;
        if (base.isEmpty()) return s;
        return base.endsWith("\n") ? base + s : base + "\n" + s;
    }

    /** 非空行数，给练习列表显示「几条」用 */
    public static long countLines(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.lines().filter((l) -> !l.isBlank()).count();
    }

    /* ================= 读写 ================= */

    /** 取全文。老的条目式数据（Note 行）第一次读时按时间顺序并进来，之后不再看那些行 */
    public String text(String practiceId) {
        return requirePractice(practiceId).getNotesText() == null
                ? migrate(practiceId) : requirePractice(practiceId).getNotesText();
    }

    /** 记事本最后修改时间；还没写过字就是 null */
    public Long updatedAt(String practiceId) {
        return requirePractice(practiceId).getNotesUpdatedAt();
    }

    public void setText(String practiceId, String text) {
        Practice p = requirePractice(practiceId);
        p.setNotesText(normalizeText(text));
        p.setNotesUpdatedAt(System.currentTimeMillis());
        practices.save(p);
    }

    public void append(String practiceId, String text) {
        Practice p = requirePractice(practiceId);
        String next = appendTo(text(practiceId), text);
        if (next.length() > Limits.MAX_NOTE_TEXT) {
            throw new IllegalArgumentException("记事本最长 " + Limits.MAX_NOTE_TEXT + " 字");
        }
        p.setNotesText(next);
        p.setNotesUpdatedAt(System.currentTimeMillis());
        practices.save(p);
    }

    /** 非空行数，给练习列表显示「几条」用 */
    public long lineCount(String practiceId) {
        return countLines(text(practiceId));
    }

    public void deleteByPractice(String practiceId) {
        notes.deleteAll(notes.findByPracticeIdOrderByCreatedAtDesc(practiceId));
        Practice p = practices.findById(practiceId).orElse(null);
        if (p != null) {
            p.setNotesText("");
            practices.save(p);
        }
    }

    /* ---------- 旧数据迁移 ---------- */

    /** 把旧的「一条一条」并成一整块：按时间正序，条与条之间空行 */
    private String migrate(String practiceId) {
        Practice p = requirePractice(practiceId);
        List<Note> old = notes.findByPracticeIdOrderByCreatedAtDesc(practiceId);
        StringBuilder sb = new StringBuilder();
        long latest = 0;
        for (int i = old.size() - 1; i >= 0; i--) { // desc → 倒过来就是时间正序
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(old.get(i).getText());
            latest = Math.max(latest, old.get(i).getUpdatedAt());
        }
        p.setNotesText(sb.toString());
        if (latest > 0) p.setNotesUpdatedAt(latest);
        practices.save(p);
        return sb.toString();
    }

    private Practice requirePractice(String practiceId) {
        return practices.findById(practiceId).orElseThrow(() -> new NotFoundException("练习不存在"));
    }
}
