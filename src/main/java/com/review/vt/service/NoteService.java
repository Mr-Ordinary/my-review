package com.review.vt.service;

import com.review.vt.pojo.entity.Note;
import com.review.vt.pojo.entity.Practice;
import com.review.vt.mapper.NoteMapper;
import com.review.vt.mapper.PracticeMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import com.review.vt.common.util.LimitsUtil;
import com.review.vt.web.ex.NotFoundException;

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

    private final NoteMapper noteMapper;
    private final PracticeMapper practiceMapper;
    private final DataCacheService dataCache;

    public NoteService(NoteMapper noteMapper, PracticeMapper practiceMapper, DataCacheService dataCache) {
        this.noteMapper = noteMapper;
        this.practiceMapper = practiceMapper;
        this.dataCache = dataCache;
    }

    /* ================= 纯逻辑（可单测） ================= */

    /** 正文清洗：空内容对「覆盖」是合法的（等于清空），只挡超长 */
    public static String normalizeText(String text) {
        String s = text == null ? "" : text;
        if (s.length() > LimitsUtil.MAX_NOTE_TEXT) {
            throw new IllegalArgumentException("记事本最长 " + LimitsUtil.MAX_NOTE_TEXT + " 字");
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

    /** 取全文（按 id）。老的条目式数据（Note 行）第一次读时按时间顺序并进来，之后不再看那些行 */
    public String text(Long practiceId) {
        return text(requirePractice(practiceId));
    }

    /** 取全文（调用方手里已有 Practice 时用这个，0 条 SQL） */
    public String text(Practice p) {
        if (p.getNotesText() == null) {
            return migrate(p);
        }
        return p.getNotesText();
    }

    /** 记事本最后修改时间；还没写过字就是 null */
    public Long updatedAt(Long practiceId) {
        return requirePractice(practiceId).getNotesUpdatedAt();
    }

    /** 记事本最后修改时间（调用方手里已有 Practice 时用这个，0 条 SQL） */
    public Long updatedAt(Practice p) {
        return p.getNotesUpdatedAt();
    }

    public void setText(Long practiceId, String text) {
        setText(requirePractice(practiceId), text);
    }

    public void setText(Practice p, String text) {
        p.setNotesText(normalizeText(text));
        p.setNotesUpdatedAt(System.currentTimeMillis());
        savePractice(p);
    }

    public void append(Long practiceId, String text) {
        append(requirePractice(practiceId), text);
    }

    public void append(Practice p, String text) {
        String next = appendTo(text(p), text);
        if (next.length() > LimitsUtil.MAX_NOTE_TEXT) {
            throw new IllegalArgumentException("记事本最长 " + LimitsUtil.MAX_NOTE_TEXT + " 字");
        }
        p.setNotesText(next);
        p.setNotesUpdatedAt(System.currentTimeMillis());
        savePractice(p);
    }

    /** 非空行数，给练习列表显示「几条」用 */
    public long lineCount(Long practiceId) {
        return countLines(text(practiceId));
    }

    public void deleteByPractice(Long practiceId) {
        noteMapper.deleteAll(noteMapper.findByPracticeIdOrderByCreatedAtDesc(practiceId));
        Practice p = practiceMapper.findById(practiceId).orElse(null);
        if (p != null) {
            p.setNotesText("");
            savePractice(p);
        }
    }

    /* ---------- 旧数据迁移 ---------- */

    /** 把旧的「一条一条」并成一整块：按时间正序，条与条之间空行 */
    private String migrate(Practice p) {
        List<Note> old = noteMapper.findByPracticeIdOrderByCreatedAtDesc(p.getId());
        StringBuilder sb = new StringBuilder();
        long latest = 0;
        for (int i = old.size() - 1; i >= 0; i--) { // desc → 倒过来就是时间正序
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(old.get(i).getText());
            latest = Math.max(latest, old.get(i).getUpdatedAt());
        }
        p.setNotesText(sb.toString());
        if (latest > 0) p.setNotesUpdatedAt(latest);
        savePractice(p);
        return sb.toString();
    }

    /**
     * 落库前清 practice:{id} 缓存（evict-before-save）。
     * 不清的话 brief/detail 从缓存读到的 notesText 会旧 20~40 分钟。
     */
    private void savePractice(Practice p) {
        dataCache.evictPracticeById(p.getId());
        practiceMapper.save(p);
    }

    private Practice requirePractice(Long practiceId) {
        return practiceMapper.findById(practiceId).orElseThrow(() -> new NotFoundException("练习不存在"));
    }
}
