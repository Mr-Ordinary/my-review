package com.review.vt.service;

import com.review.vt.pojo.entity.MemoryPoint;
import com.review.vt.mapper.MemoryPointMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.review.vt.common.util.MappingsUtil;

/**
 * 记忆点：批改时 AI 给的「能直接背诵的一句话」，挂在知识点名下。
 *
 * 以前它只拼进点评文本、看完就散——一个复习系统把最该攒的东西扔掉了。
 * 现在结构化存下来，复习某个知识点时能直接看到「这个点该背什么」。
 *
 * 幂等：同一道题的同一句话不重复入（批改续跑会再走一遍）。
 */
@Service
public class MemoryPointService {

    /** 一个练习的记忆点上限，防止批改输出异常时刷爆 */
    static final int MAX_PER_PRACTICE = 2000;

    private final MemoryPointMapper memoryPointMapper;

    public MemoryPointService(MemoryPointMapper memoryPointMapper) {
        this.memoryPointMapper = memoryPointMapper;
    }

    /* ================= 纯逻辑（可单测） ================= */

    /** 单条清洗：去首尾空白、砍超长。空的直接丢，不占条目 */
    public static String normalizeText(String text) {
        if (text == null) return "";
        String s = text.trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** 去重键：同一道题的同一句话只算一条 */
    static String keyOf(Long questionId, String text) {
        return questionId + " " + text;
    }

    /**
     * 纯函数：算出该落库的新记忆点。不碰 repo，可直接单测。
     *
     * @param pointNames 这道题挂的知识点（通常等于 assignedPoints）
     * @param texts      AI 给的可背诵句子
     * @param existing   已有的去重键，会被就地补上新条目
     */
    public static List<MemoryPoint> build(Long practiceId, Long questionId,
                                          List<String> pointNames, List<String> texts,
                                          Set<String> existing, long now) {
        List<MemoryPoint> out = new ArrayList<>();
        if (texts == null || texts.isEmpty()) return out;
        List<String> names = pointNames == null ? List.of() : pointNames.stream()
                .filter((n) -> n != null && !n.isBlank()).distinct().toList();
        for (String raw : texts) {
            String t = normalizeText(raw);
            if (t.isEmpty()) continue;
            String key = keyOf(questionId, t);
            if (existing.contains(key)) continue;
            if (existing.size() + out.size() >= MAX_PER_PRACTICE) break;
            MemoryPoint m = new MemoryPoint();
            m.setPracticeId(practiceId);
            m.setQuestionId(questionId);
            m.setPointNamesJson(MappingsUtil.writeStringList(names));
            m.setText(t);
            m.setCreatedAt(now);
            out.add(m);
            existing.add(key);
        }
        return out;
    }

    /* ================= 读写 ================= */

    /** 交卷批改时收集。同一道题同一句不重复 */
    public List<MemoryPoint> collect(Long practiceId, Long questionId,
                                     List<String> pointNames, List<String> texts) {
        Set<String> seen = existingKeys(practiceId);
        List<MemoryPoint> fresh = build(practiceId, questionId, pointNames, texts, seen, System.currentTimeMillis());
        for (MemoryPoint m : fresh) memoryPointMapper.save(m);
        return fresh;
    }

    /** 该练习已有记忆点的去重键（批改开始时载一次，整卷共享——别逐题查全表） */
    public Set<String> existingKeys(Long practiceId) {
        Set<String> seen = new LinkedHashSet<>();
        for (MemoryPoint m : memoryPointMapper.findByPracticeIdOrderByCreatedAtDesc(practiceId)) {
            seen.add(keyOf(m.getQuestionId(), m.getText()));
        }
        return seen;
    }

    /** 批量落库（严禁循环插入）：整批一次 insertByList（超长 BatchWriter 拆条）。行由 build 攒 */
    public void insertAll(List<MemoryPoint> rows) {
        if (rows == null || rows.isEmpty()) return;
        Long uid = com.review.vt.auth.AuthContext.require().userId();
        for (MemoryPoint m : rows) {
            if (m.getUserId() == null) m.setUserId(uid);
        }
        com.review.vt.common.util.BatchWriter.write(rows, memoryPointMapper::insertByList);
    }

    /** 按知识点名归组：点名 → 可背诵句子（最新在前，同句去重） */
    public Map<String, List<String>> byPointName(Long practiceId) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (MemoryPoint m : memoryPointMapper.findByPracticeIdOrderByCreatedAtDesc(practiceId)) {
            for (String name : MappingsUtil.parseStringList(m.getPointNamesJson())) {
                List<String> list = out.computeIfAbsent(name, k -> new ArrayList<>());
                if (!list.contains(m.getText())) list.add(m.getText());
            }
        }
        return out;
    }

    public List<MemoryPoint> listOf(Long practiceId) {
        return memoryPointMapper.findByPracticeIdOrderByCreatedAtDesc(practiceId);
    }

    /** 级联删除用（删练习/删阶段） */
    public void deleteByPractice(Long practiceId) {
        memoryPointMapper.deleteAll(memoryPointMapper.findByPracticeIdOrderByCreatedAtDesc(practiceId));
    }
}
