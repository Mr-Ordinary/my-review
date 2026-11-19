package com.review.vt.service;

import com.review.vt.common.config.CacheProperties;
import com.review.vt.mapper.AttemptMapper;
import com.review.vt.mapper.KnowledgePointMapper;
import com.review.vt.mapper.PaperMapper;
import com.review.vt.mapper.PracticeMapper;
import com.review.vt.mapper.QuestionMapper;
import com.review.vt.mapper.ReviewCardMapper;
import com.review.vt.mapper.ReviewDocMapper;
import com.review.vt.mapper.StageMapper;
import com.review.vt.pojo.entity.Paper;
import com.review.vt.pojo.entity.Practice;
import com.review.vt.pojo.entity.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 实体缓存纪律：cache-aside（miss 查库回填）、批量 MGET 只查 miss、
 * Redis 挂降级直查 DB 不抛、evict 尽力而为。
 * 点查键（stage/practice/paper/question/attempt/knowledge_point/review_card:{id}）+ 两级列表键（*_ids:{父id}）。
 * 全局点查键命中后校验行内 userId，不一致当 miss 且不碰别人的键；列表聚合永不缓存。
 */
class DataCacheServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private StageMapper stageMapper;
    private PracticeMapper practiceMapper;
    private PaperMapper paperMapper;
    private QuestionMapper questionMapper;
    private AttemptMapper attemptMapper;
    private KnowledgePointMapper kpMapper;
    private ReviewDocMapper docMapper;
    private ReviewCardMapper cardMapper;
    private DataCacheService svc;

    private static Practice practice(long id, long stageId, long createdAt) {
        Practice p = new Practice();
        p.setId(id);
        p.setStageId(stageId);
        p.setCreatedAt(createdAt);
        return p;
    }

    private static Stage stage(long id, long userId) {
        Stage s = new Stage();
        s.setId(id);
        s.setUserId(userId);
        s.setName("t");
        return s;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        stageMapper = mock(StageMapper.class);
        practiceMapper = mock(PracticeMapper.class);
        paperMapper = mock(PaperMapper.class);
        questionMapper = mock(QuestionMapper.class);
        attemptMapper = mock(AttemptMapper.class);
        kpMapper = mock(KnowledgePointMapper.class);
        docMapper = mock(ReviewDocMapper.class);
        cardMapper = mock(ReviewCardMapper.class);
        svc = new DataCacheService(redis, new CacheProperties(),
                stageMapper, practiceMapper, paperMapper, questionMapper, attemptMapper,
                kpMapper, docMapper, cardMapper);
    }

    @Test
    @DisplayName("两级缓存命中（ids + 逐条点查）→ 不查库不回填")
    void hitSkipsLoader() {
        when(valueOps.get("knowledge_point_ids:5")).thenReturn("[2]");
        when(valueOps.get("knowledge_point:2")).thenReturn("{\"id\":2,\"userId\":2,\"practiceId\":5}");
        var got = svc.knowledgePointsOfPractice(2L, 5L);
        assertEquals(1, got.size());
        assertEquals(2L, got.get(0).getId());
        verifyNoInteractions(kpMapper);
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("ids 过期/缺失 → 一条 SQL 整组重新初始化并回填两级缓存")
    void idsMissReloadsAll() {
        when(valueOps.get("knowledge_point_ids:5")).thenReturn(null);
        com.review.vt.pojo.entity.KnowledgePoint kp = new com.review.vt.pojo.entity.KnowledgePoint();
        kp.setId(2L);
        kp.setUserId(2L);
        kp.setPracticeId(5L);
        when(kpMapper.selectByPracticeAsc(2L, 5L)).thenReturn(List.of(kp));

        var got = svc.knowledgePointsOfPractice(2L, 5L);
        assertEquals(1, got.size());
        verify(valueOps).set(eq("knowledge_point_ids:5"), eq("[2]"), anyLong(), any());
        verify(valueOps).set(eq("knowledge_point:2"), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("Redis 读挂 → 点查降级直查 DB，不抛")
    void redisDownFallsBackToDb() {
        when(valueOps.get("stage:3")).thenThrow(Mockito.mock(DataAccessException.class));
        when(stageMapper.selectByIdScoped(3L, 7L)).thenReturn(stage(3, 7));
        assertNotNull(svc.stageById(7L, 3L), "数据不丢");
    }

    @Test
    @DisplayName("ids 坏 JSON → 当 miss 整组重新初始化")
    void badJsonTreatedAsMiss() {
        when(valueOps.get("knowledge_point_ids:5")).thenReturn("{坏 json");
        when(kpMapper.selectByPracticeAsc(2L, 5L)).thenReturn(List.of());
        assertTrue(svc.knowledgePointsOfPractice(2L, 5L).isEmpty());
        verify(kpMapper).selectByPracticeAsc(2L, 5L);
    }

    @Test
    @DisplayName("evict 删对应键；Redis 挂不抛")
    void evictDeletesAndSurvives() {
        svc.evictPaperIds(9L);
        verify(redis).delete("paper_ids:9");

        when(redis.delete(anyString())).thenThrow(Mockito.mock(DataAccessException.class));
        assertDoesNotThrow(() -> svc.evictKnowledgePointIds(1L));
    }

    @Test
    @DisplayName("warm 用 SETNX（不覆盖并发新值）且带 TTL")
    void warmUsesSetIfAbsent() {
        svc.warmKnowledgePointIds(5L, List.of(2L));
        verify(valueOps).setIfAbsent(eq("knowledge_point_ids:5"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("paper_ids 缺失 → 一条 SQL 重建 id 列表；卷走 paper:{id} 点查")
    void papersIdsFilled() {
        when(valueOps.get("paper_ids:1")).thenReturn(null);
        Paper pa = new Paper();
        pa.setId(10L);
        pa.setPracticeId(1L);
        pa.setUserId(2L);
        pa.setSequence(1);
        when(paperMapper.selectByPracticeSeqAsc(2L, 1L)).thenReturn(List.of(pa));
        when(valueOps.get("paper:10")).thenReturn(null);
        when(paperMapper.selectByIdScoped(10L, 2L)).thenReturn(pa);
        when(questionMapper.selectStubsByPaperId(2L, 10L)).thenReturn(List.of());

        var got = svc.paperOutlinesOfPractice(2L, 1L);
        assertEquals(1, got.size());
        verify(valueOps).set(eq("paper_ids:1"), eq("[10]"), anyLong(), any());
        verify(valueOps).set(eq("paper:10"), anyString(), anyLong(), any());
    }

    /* ---------- stage:{id} 单键点查（跨用户靠行内 userId 校验） ---------- */

    @Test
    @DisplayName("stageById 未命中 → 查库 → 回填 stage:{id}；查无此行不回填")
    void stageByIdMissLoadsAndFills() {
        when(valueOps.get("stage:3")).thenReturn(null);
        when(stageMapper.selectByIdScoped(3L, 7L)).thenReturn(stage(3, 7));

        Stage got = svc.stageById(7L, 3L);
        assertNotNull(got);
        assertEquals(7L, got.getUserId());
        verify(valueOps).set(eq("stage:3"), anyString(), anyLong(), eq(TimeUnit.SECONDS));

        // 查无此行：不回填
        when(valueOps.get("stage:4")).thenReturn(null);
        when(stageMapper.selectByIdScoped(4L, 7L)).thenReturn(null);
        assertNull(svc.stageById(7L, 4L));
        verify(valueOps, never()).set(eq("stage:4"), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("stageById 命中且归属正确 → 不查库")
    void stageByIdHitSkipsDb() {
        when(valueOps.get("stage:3")).thenReturn("{\"id\":3,\"userId\":7,\"name\":\"t\"}");
        Stage got = svc.stageById(7L, 3L);
        assertNotNull(got);
        assertEquals(3L, got.getId());
        verifyNoInteractions(stageMapper);
    }

    @Test
    @DisplayName("跨用户命中别人的键 → 当 miss 走 scoped 查库，不 DEL 也不覆盖别人的键")
    void stageByIdCrossUserFallsToDbWithoutTouchingKey() {
        when(valueOps.get("stage:3")).thenReturn("{\"id\":3,\"userId\":7,\"name\":\"t\"}");
        when(stageMapper.selectByIdScoped(3L, 9L)).thenReturn(null);   // scoped 查无

        assertNull(svc.stageById(9L, 3L));
        verify(stageMapper).selectByIdScoped(3L, 9L);
        verify(valueOps, never()).set(eq("stage:3"), anyString(), anyLong(), any());
        verify(redis, never()).delete("stage:3");
    }

    @Test
    @DisplayName("stageById 坏 JSON → 删脏键当 miss 重查")
    void stageByIdBadJsonTreatedAsMiss() {
        when(valueOps.get("stage:3")).thenReturn("{坏 json");
        when(stageMapper.selectByIdScoped(3L, 7L)).thenReturn(stage(3, 7));
        assertNotNull(svc.stageById(7L, 3L));
        verify(redis).delete("stage:3");
        verify(stageMapper).selectByIdScoped(3L, 7L);
    }

    @Test
    @DisplayName("evictStage 删 stage:{id}；Redis 挂不抛")
    void evictStageDeletes() {
        svc.evictStage(3L);
        verify(redis).delete("stage:3");

        when(redis.delete(anyString())).thenThrow(Mockito.mock(DataAccessException.class));
        assertDoesNotThrow(() -> svc.evictStage(3L));
    }
}
