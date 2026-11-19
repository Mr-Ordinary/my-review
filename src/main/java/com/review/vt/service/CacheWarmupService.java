package com.review.vt.service;

import com.review.vt.common.config.CacheProperties;
import com.review.vt.mapper.KnowledgePointMapper;
import com.review.vt.mapper.PaperMapper;
import com.review.vt.mapper.PracticeMapper;
import com.review.vt.mapper.ReviewCardMapper;
import com.review.vt.mapper.ReviewDocMapper;
import com.review.vt.pojo.entity.KnowledgePoint;
import com.review.vt.pojo.entity.Paper;
import com.review.vt.pojo.entity.Practice;
import com.review.vt.pojo.entity.ReviewCard;
import com.review.vt.pojo.entity.ReviewDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 启动缓存热备：异步把「读多写少」的热数据（练习/卷/知识点/复习资料）扫进 Redis，
 * 让热路径开机即命中。（阶段行不缓存——首页直查 DB，不热备。）
 *
 * <p><b>热点识别是类型级</b>：问题与作答两张大表**不扫**（它们永不缓存，读时直查 DB）。
 * 表内用主键键集分页（WHERE id &gt; ? ORDER BY id LIMIT n），不全表一次性载入，扫描压力有界。
 * 写入用 SETNX：启动后并发读写产生的新值不会被旧扫描结果覆盖。
 * Redis 失败记日志静默中止，绝不影响服务。
 *
 * <p>时机：ApplicationReadyEvent（在 SchemaV2Runner/ReviewCardRunner 等 CommandLineRunner 之后——
 * ReviewCardRunner 会重写 review_card，热备必须见最终数据），守护线程执行，不阻塞启动。
 */
@Component
public class CacheWarmupService {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmupService.class);

    private final CacheProperties props;
    private final DataCacheService dataCache;
    private final PracticeMapper practiceMapper;
    private final PaperMapper paperMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final ReviewDocMapper reviewDocMapper;
    private final ReviewCardMapper reviewCardMapper;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "myreview-cache-warmup");
        t.setDaemon(true);
        return t;
    });

    public CacheWarmupService(CacheProperties props, DataCacheService dataCache,
                              PracticeMapper practiceMapper, PaperMapper paperMapper,
                              KnowledgePointMapper knowledgePointMapper, ReviewDocMapper reviewDocMapper,
                              ReviewCardMapper reviewCardMapper) {
        this.props = props;
        this.dataCache = dataCache;
        this.practiceMapper = practiceMapper;
        this.paperMapper = paperMapper;
        this.knowledgePointMapper = knowledgePointMapper;
        this.reviewDocMapper = reviewDocMapper;
        this.reviewCardMapper = reviewCardMapper;
    }

    /* ==================== 热备已停用（2026-11-18） ====================
       原因：系统大量文字数据（题面/答案/批改反馈/知识点全文），热备一次性灌 Redis 占内存风险大。
       先注释掉不删，待后续评估（如只热 id 级数据）再启用。 */

    // @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("[cache-warmup] 已停用（代码注释保留，见 CacheWarmupService 说明）");
        // if (!props.isWarmupEnabled()) {
        //     log.info("[cache-warmup] 已关闭（myreview.cache.warmup-enabled=false）");
        //     return;
        // }
        // executor.submit(this::runQuietly);
    }

    /** 全程兜底：任何异常只记日志，绝不影响服务 */
    void runQuietly() {
        try {
            long t0 = System.currentTimeMillis();
            int keys = 0;

            keys += warmKps();
            keys += warmDocs();
            keys += warmCards();
            log.info("[cache-warmup] 完成：{} 个键，{} ms", keys, System.currentTimeMillis() - t0);
        } catch (RuntimeException e) {
            log.warn("[cache-warmup] 中止：{}", e.getMessage());
        }
    }

    /* ==================== 每表：键集分块 → 按父分组 → SETNX ====================
       缓存键 = 表名:userId:父主键，分组按父主键，userId 取行实体（同组同人）。 */

    private int warmKps() {
        // 两级键：knowledge_point:{id} 逐行 + knowledge_point_ids:{practiceId} id 列表
        Map<Long, List<Long>> idsByPractice = new LinkedHashMap<>();
        int keys = 0;
        for (KnowledgePoint row : chunk((afterId, limit) -> knowledgePointMapper.selectChunkForWarmup(afterId, limit), KnowledgePoint::getId)) {
            dataCache.warmKnowledgePoint(row);
            idsByPractice.computeIfAbsent(row.getPracticeId(), k -> new ArrayList<>()).add(row.getId());
            keys++;
        }
        for (Map.Entry<Long, List<Long>> e : idsByPractice.entrySet()) {
            e.getValue().sort(Long::compare);
            dataCache.warmKnowledgePointIds(e.getKey(), e.getValue());
            keys++;
        }
        log.info("[cache-warmup] knowledge_point: 行+ids 共 {} 键", keys);
        return keys;
    }

    private int warmDocs() {
        int keys = 0, rows = 0;
        for (ReviewDoc row : chunk((afterId, limit) -> reviewDocMapper.selectChunkForWarmup(afterId, limit), ReviewDoc::getId)) {
            dataCache.warmReviewDoc(row);
            keys++;
            rows++;
        }
        log.info("[cache-warmup] review_doc: {} 行, {} 键", rows, keys);
        return keys;
    }

    private int warmCards() {
        // 两级键：review_card:{id} 逐行 + review_card_ids:{reviewDocId} id 列表
        Map<Long, List<Long>> idsByDoc = new LinkedHashMap<>();
        int keys = 0;
        for (ReviewCard row : chunk((afterId, limit) -> reviewCardMapper.selectChunkForWarmup(afterId, limit), ReviewCard::getId)) {
            dataCache.warmReviewCard(row);
            idsByDoc.computeIfAbsent(row.getReviewDocId(), k -> new ArrayList<>()).add(row.getId());
            keys++;
        }
        for (Map.Entry<Long, List<Long>> e : idsByDoc.entrySet()) {
            e.getValue().sort(Long::compare);
            dataCache.warmReviewCardIds(e.getKey(), e.getValue());
            keys++;
        }
        log.info("[cache-warmup] review_card: 行+ids 共 {} 键", keys);
        return keys;
    }

    /* ==================== 内部 ==================== */

    @FunctionalInterface
    private interface ChunkLoader<T> {
        List<T> load(Long afterId, int limit);
    }

    /** 主键键集分页：不一次性载入整表，块间以上一块末行 id 为游标 */
    private <T> List<T> chunk(ChunkLoader<T> loader, java.util.function.Function<T, Long> idOf) {
        List<T> all = new ArrayList<>();
        long last = 0;
        for (;;) {
            List<T> rows = loader.load(last, props.getWarmupChunkSize());
            if (rows == null || rows.isEmpty()) break;
            all.addAll(rows);
            last = idOf.apply(rows.get(rows.size() - 1));
            if (rows.size() < props.getWarmupChunkSize()) break;
        }
        return all;
    }

    private static int count(Map<Long, ? extends List<?>> m) {
        int n = 0;
        for (List<?> v : m.values()) n += v.size();
        return n;
    }
}
