package com.review.vt.common.util;

import com.review.vt.pojo.entity.Attempt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段详情均分口径：得分 = 每题最近一次已批改作答的实得分；
 * 均分 = Σ得分（未批改计 0）/ 题数；题数 0 或无一题批改 → null。
 */
class MappingsUtilEarnedAvgTest {

    private static Attempt attempt(long questionId, Double points, Long evaluatedAt) {
        Attempt a = new Attempt();
        a.setQuestionId(questionId);
        a.setPoints(points);
        a.setEvaluatedAt(evaluatedAt);
        return a;
    }

    @Test
    @DisplayName("latestEarned：最近一次已批改为准，未批改行不覆盖")
    void latestEarnedTakesLastGraded() {
        assertNull(MappingsUtil.latestEarned(List.of()));
        assertNull(MappingsUtil.latestEarned(null), "空输入当没批改");
        assertNull(MappingsUtil.latestEarned(List.of(attempt(1, null, null))), "没批改过 → null");
        assertEquals(5.0, MappingsUtil.latestEarned(List.of(attempt(1, 5.0, 100L), attempt(1, null, null))),
                "后面有未批改行也不覆盖已批改值");
        assertEquals(7.0, MappingsUtil.latestEarned(List.of(attempt(1, 5.0, 100L), attempt(1, 7.0, 200L))),
                "多次批改取最后一次");
    }

    @Test
    @DisplayName("earnedAvg：空/全未批改 → null；未批改进分母；保留 1 位小数")
    void earnedAvgSemantics() {
        assertNull(MappingsUtil.earnedAvg(0, 0, 0), "没有题 → null");
        assertNull(MappingsUtil.earnedAvg(4, 0, 0), "一题都没批改 → null（不显示 0 分）");
        assertEquals(7.5, MappingsUtil.earnedAvg(2, 15.0, 2));
        assertEquals(6.3, MappingsUtil.earnedAvg(4, 25.0, 2), "未批改计 0 进分母：25/4=6.25 → 6.3");
        assertEquals(3.3, MappingsUtil.earnedAvg(3, 10.0, 1), "10/3=3.333 → 3.3");
    }

    @Test
    @DisplayName("earnedAvg 阶段口径 = 总分/总题数（题目加权，不是各练习均分的平均）")
    void stageAvgIsQuestionWeighted() {
        // 练习 A：2 题得 10 分（均 5）；练习 B：8 题得 20 分（均 2.5）→ 加权 30/10=3，而非 (5+2.5)/2=3.75
        double stageAvg = MappingsUtil.earnedAvg(2 + 8, 10 + 20, 2 + 8);
        assertEquals(3.0, stageAvg);
    }

    @Test
    @DisplayName("groupByQuestion：按题分组保持行序（sequence 序由 SQL 保证）")
    void groupByQuestionKeepsOrder() {
        var got = MappingsUtil.groupByQuestion(List.of(
                attempt(5, 1.0, 1L), attempt(6, 2.0, 1L), attempt(5, 3.0, 2L)));
        assertEquals(2, got.size());
        assertEquals(List.of(1.0, 3.0), got.get(5L).stream().map(Attempt::getPoints).toList());
        assertEquals(List.of(2.0), got.get(6L).stream().map(Attempt::getPoints).toList());
    }
}
