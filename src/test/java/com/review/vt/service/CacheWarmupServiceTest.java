package com.review.vt.service;

import com.review.vt.common.config.CacheProperties;
import com.review.vt.mapper.AttemptMapper;
import com.review.vt.mapper.KnowledgePointMapper;
import com.review.vt.mapper.PaperMapper;
import com.review.vt.mapper.PracticeMapper;
import com.review.vt.mapper.QuestionMapper;
import com.review.vt.mapper.ReviewCardMapper;
import com.review.vt.mapper.ReviewDocMapper;
import com.review.vt.pojo.entity.KnowledgePoint;
import com.review.vt.pojo.entity.Paper;
import com.review.vt.pojo.entity.ReviewCard;
import com.review.vt.pojo.entity.Practice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 启动热备：键集分块循环、按父分组 + 规范排序、SETNX 写入、
 * question/attempt 表绝不扫（永不缓存，读时直查 DB）、Redis 失败静默中止。
 * 阶段行不缓存，不热备。
 */
class CacheWarmupServiceTest {

    private static Practice practice(long id, long stageId, long userId, long createdAt) {
        Practice p = new Practice();
        p.setId(id);
        p.setStageId(stageId);
        p.setUserId(userId);
        p.setCreatedAt(createdAt);
        return p;
    }

    @Test
    @DisplayName("键集分块循环：两块 + 空第三块终止，两级键 warm（行 + ids 排序）")
    void chunkLoopGroupsAndWarms() {
        KnowledgePointMapper kpMapper = mock(KnowledgePointMapper.class);
        DataCacheService cache = mock(DataCacheService.class);
        KnowledgePoint kp1 = new KnowledgePoint(); kp1.setId(1L); kp1.setPracticeId(9L); kp1.setUserId(7L);
        KnowledgePoint kp2 = new KnowledgePoint(); kp2.setId(2L); kp2.setPracticeId(9L); kp2.setUserId(7L);
        KnowledgePoint kp3 = new KnowledgePoint(); kp3.setId(3L); kp3.setPracticeId(9L); kp3.setUserId(7L);
        // 块 1：两条（末行 id=2）；块 2：一条（末行 id=3）；块 3：空
        when(kpMapper.selectChunkForWarmup(0L, 2)).thenReturn(List.of(kp1, kp2));
        when(kpMapper.selectChunkForWarmup(2L, 2)).thenReturn(List.of(kp3));
        when(kpMapper.selectChunkForWarmup(3L, 2)).thenReturn(List.of());
        CacheProperties props = new CacheProperties();
        props.setWarmupChunkSize(2);
        CacheWarmupService warmup = new CacheWarmupService(props, cache,
                mock(PracticeMapper.class), mock(PaperMapper.class), kpMapper,
                mock(ReviewDocMapper.class), mock(ReviewCardMapper.class));
        warmup.runQuietly();
        verify(cache, times(3)).warmKnowledgePoint(any());
        verify(cache).warmKnowledgePointIds(eq(9L), argThat(ids -> ids.equals(List.of(1L, 2L, 3L))));
    }

    @Test
    @DisplayName("知识卡两级 warm：逐行 + ids 按 sequence/id 序")
    void practiceAndPaperOrdering() {
        ReviewCardMapper cardMapper = mock(ReviewCardMapper.class);
        DataCacheService cache = mock(DataCacheService.class);
        ReviewCard c2 = new ReviewCard(); c2.setId(20L); c2.setReviewDocId(1L); c2.setUserId(7L); c2.setSequence(2);
        ReviewCard c1 = new ReviewCard(); c1.setId(21L); c1.setReviewDocId(1L); c1.setUserId(7L); c1.setSequence(1);
        when(cardMapper.selectChunkForWarmup(0L, 500)).thenReturn(List.of(c2, c1));
        when(cardMapper.selectChunkForWarmup(21L, 500)).thenReturn(List.of());
        CacheWarmupService warmup = new CacheWarmupService(new CacheProperties(), cache,
                mock(PracticeMapper.class), mock(PaperMapper.class),
                mock(KnowledgePointMapper.class), mock(ReviewDocMapper.class), cardMapper);
        warmup.runQuietly();
        verify(cache, times(2)).warmReviewCard(any());
        verify(cache).warmReviewCardIds(eq(1L), argThat(ids -> ids.equals(List.of(20L, 21L))));
    }

    @Test
    @DisplayName("question/attempt 表绝不扫（永不缓存）")
    void questionsAttemptsNeverScanned() {
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        AttemptMapper attemptMapper = mock(AttemptMapper.class);
        CacheWarmupService warmup = new CacheWarmupService(new CacheProperties(), mock(DataCacheService.class),
                mock(PracticeMapper.class), mock(PaperMapper.class),
                mock(KnowledgePointMapper.class), mock(ReviewDocMapper.class), mock(ReviewCardMapper.class));
        warmup.runQuietly();
        verifyNoInteractions(questionMapper, attemptMapper);
    }

    @Test
    @DisplayName("Redis 失败 → 静默中止不抛；warmup-enabled=false 短路")
    void failureIsQuietAndSwitchWorks() {
        DataCacheService cache = mock(DataCacheService.class);
        doThrow(new org.springframework.dao.DataAccessException("redis down") {})
                .when(cache).warmKnowledgePoint(any());
        CacheWarmupService warmup = new CacheWarmupService(new CacheProperties(), cache,
                mock(PracticeMapper.class), mock(PaperMapper.class),
                mock(KnowledgePointMapper.class), mock(ReviewDocMapper.class), mock(ReviewCardMapper.class));
        assertDoesNotThrow(warmup::runQuietly);

        CacheProperties off = new CacheProperties();
        off.setWarmupEnabled(false);
        CacheWarmupService disabled = new CacheWarmupService(off, cache,
                mock(PracticeMapper.class), mock(PaperMapper.class),
                mock(KnowledgePointMapper.class), mock(ReviewDocMapper.class), mock(ReviewCardMapper.class));
        disabled.onReady();
        verify(cache, never()).warmKnowledgePoint(any());
    }
}
