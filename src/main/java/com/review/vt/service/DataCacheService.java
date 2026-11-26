package com.review.vt.service;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.vt.common.config.CacheProperties;
import com.review.vt.common.util.MappingsUtil;
import com.review.vt.mapper.AttemptMapper;
import com.review.vt.mapper.KnowledgePointMapper;
import com.review.vt.mapper.PaperMapper;
import com.review.vt.mapper.PracticeMapper;
import com.review.vt.mapper.QuestionMapper;
import com.review.vt.mapper.ReviewCardMapper;
import com.review.vt.mapper.ReviewDocMapper;
import com.review.vt.mapper.StageMapper;
import com.review.vt.pojo.dto.PaperOutlineDto;
import com.review.vt.pojo.dto.QuestionDetailDto;
import com.review.vt.pojo.dto.QuestionStubDto;
import com.review.vt.pojo.entity.Attempt;
import com.review.vt.pojo.entity.KnowledgePoint;
import com.review.vt.pojo.entity.Paper;
import com.review.vt.pojo.entity.Practice;
import com.review.vt.pojo.entity.Question;
import com.review.vt.pojo.entity.ReviewCard;
import com.review.vt.pojo.entity.ReviewDoc;
import com.review.vt.pojo.entity.Stage;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 数据缓存（Redis JSON）：只缓存**数据库表行**。点查键 {表名}:{id}，两级列表键 {表名}_ids:{父id} + 行点查：
 *
 * <pre>
 * 点查键（{表名}:{id}，全局键）：
 * stage:{id}                         → 阶段行
 * practice:{id}                      → 练习行
 * paper:{id}                         → 卷大纲（卷行 + 题目存根）
 * question:{id}                      → 题内容（含 latestAttemptId 指针）
 * attempt:{id}                       → 作答行
 * knowledge_point:{id}               → 单条知识点行
 * review_doc:{practiceId}            → 该练习的复习文档行（0..1）
 * latest_paper:{practiceId}          → 该练习最新卷 id（出新卷时更新，刷题卷不写）
 * 列表/派生键：
 * knowledge_point_ids:{practiceId}   → 该练习全部知识点 id 列表（过期即整组视同过期，重新初始化）
 * review_card_ids:{reviewDocId}      → 该文档的知识卡 id 列表（卡本身走 review_card:{id} 点查）
 *
 * ids 索引最深只到练习（如 practice_ids:stage_id:{id}，在 StageService 维护）：
 * 比练习更深的（卷/题）不建 ids 索引——数据量大、失效面广，列表一律直查 DB。
 * </pre>
 *
 * <p><b>点查走缓存、范围/列表走 DB</b>：点查键 1:1 对应一行/一聚合，写路径只打脏一个键。
 * 全局键（无 userId）跨用户安全靠行内 userId 校验——命中后 {@code 行.userId == 请求者} 才算命中；
 * 不一致当 miss 走 scoped 查库，**绝不 DEL/覆盖别人的键**；查无此行不回填。
 * 写路径失效纪律：保存答案/揭示答案 → del question:{id}+paper:{id}；批改刷盘 → del attempt:{id}；
 * 阶段行写（总结落库/删除）双删 stage:{id}；删练习/删阶段级联清全部点查键 + 最新卷。
 * <p><b>统计（均分等）与列表聚合永不缓存</b>——每次读现算；题目/作答的**列表**也永不缓存
 * （批量直查一条 IN），只有按 id 的点查走上面的缓存。
 *
 * <p>纪律：修改前删除（evictXxx）+ 未命中查库回填 + TTL 兜底（漏删时自动过期，不追一致性）。
 * 读失败（Redis 挂/坏 JSON）→ 当 miss 直查 DB，不回填不抛。
 *
 * <p>写路径删除失败靠 TTL 兜底；热备用 SETNX 不覆盖并发新值。与鉴权缓存（UserCacheService）相互独立。
 */
@Service
@AllArgsConstructor
public class DataCacheService {

  private static final Logger log = LoggerFactory.getLogger(DataCacheService.class);

  private static final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private final StringRedisTemplate redis;
  private final CacheProperties props;
  private final StageMapper stageMapper;
  private final PracticeMapper practiceMapper;
  private final PaperMapper paperMapper;
  private final QuestionMapper questionMapper;
  private final AttemptMapper attemptMapper;
  private final KnowledgePointMapper knowledgePointMapper;
  private final ReviewDocMapper reviewDocMapper;
  private final ReviewCardMapper reviewCardMapper;

  /* ==================== key：表名:userId:父主键 ==================== */

  static String stageKey(Long id) {
    return "stage:" + id;
  }

  static String practiceByIdKey(Long id) {
    return "practice:" + id;
  }

  static String paperByIdKey(Long id) {
    return "paper:" + id;
  }

  static String questionByIdKey(Long id) {
    return "question:" + id;
  }

  static String attemptByIdKey(Long id) {
    return "attempt:" + id;
  }

  static String latestPaperKey(Long practiceId) {
    return "latest_paper:" + practiceId;
  }

  static String knowledgePointIdsKey(Long practiceId) {
    return "knowledge_point_ids:" + practiceId;
  }

  static String knowledgePointByIdKey(Long id) {
    return "knowledge_point:" + id;
  }

  static String reviewDocKey(Long practiceId) {
    return "review_doc:" + practiceId;
  }

  static String reviewCardIdsKey(Long reviewDocId) {
    return "review_card_ids:" + reviewDocId;
  }

  static String reviewCardByIdKey(Long id) {
    return "review_card:" + id;
  }

  /* ==================== 读（cache-aside：miss → 查库回填） ==================== */


  /**
   * 查询
   */
  public <T> T getObj(String k, Class<T> cls) {
    String v = getQuiet(k);
    if (v == null) return null;
    try {
      return JSON.parseObject(v, cls);
    } catch (Exception e) {
      jsonParseErrLog(k, v);
      return null;
    }
  }

  public <T> List<T> getList(String k, Class<T> cls) {
    String v = getQuiet(k);
    if (v == null) return null;
    try {
      return JSON.parseArray(v, cls);
    } catch (Exception e) {
      jsonParseErrLog(k, v);
      return null;
    }
  }

  public void set(String k, Object v) {
    redis.opsForValue().set(
        k,
        JSON.toJSONString(v),
        props.randomTtlSeconds(),
        TimeUnit.SECONDS);
  }

  public void delete(String k) {
    redis.delete(k);
  }


  private void jsonParseErrLog(String k, String v) {
    log.warn("JSON解析失败,KEY:{},VAL:{}", k, v);
  }

  /**
   * ids
   */
  private List<Long> parseIds(String val) {
    if (val == null || val.isBlank()) return null;
    try {
      return json.readValue(val, new TypeReference<List<Long>>() {
      });
    } catch (Exception e) {
      return null;
    }
  }


  /**
   * 点查键通用骨架（{表名}:{id}，全局键）：命中且行内 userId == 请求者才算命中；
   * 不一致当 miss 走 scoped 查库，**不 DEL 也不覆盖别人的键**（防跨用户驱逐/投毒）；
   * 坏 JSON 删脏键当 miss；查无此行**不回填**返回 null。
   */
  private <T> T pointAside(String key, Long userId, java.util.function.Function<T, Long> ownerOf,
                           TypeReference<T> type, java.util.function.Supplier<T> loader) {
    String val = getQuiet(key);
    if (val != null) {
      T cached = parse(val, type);
      if (cached == null) {
        delQuiet(key);   // 坏 JSON：删脏键当 miss 重查
      } else {
        Long owner = ownerOf.apply(cached);
        if (userId != null && userId.equals(owner)) return cached;
        // userId 不一致：当 miss 落库，不碰别人的键
      }
    }
    try {
      T loaded = loader.get();
      if (loaded != null) setQuiet(key, toJson(loaded));
      return loaded;
    } catch (DataAccessException e) {
      warnOnce("查询失败，本次不回填", e);
      return null;
    }
  }

  /**
   * 阶段行点查：stage:{id}
   */
  public Stage stageById(Long userId, Long id) {
    return pointAside(stageKey(id), userId, Stage::getUserId,
        new TypeReference<Stage>() {
        }, () -> stageMapper.selectByIdScoped(id, userId));
  }

  /**
   * 练习行点查：practice:{id}
   */
  public Practice practiceById(Long userId, Long id) {
    return pointAside(practiceByIdKey(id), userId, Practice::getUserId,
        new TypeReference<Practice>() {
        }, () -> practiceMapper.selectByIdScoped(id, userId));
  }

  /**
   * 卷大纲点查：paper:{id}（卷行 + 题目存根 answered/revealed 标记）
   */
  public PaperOutlineDto paperById(Long userId, Long id) {
    return pointAside(paperByIdKey(id), userId, PaperOutlineDto::userId,
        new TypeReference<PaperOutlineDto>() {
        }, () -> loadPaperOutline(userId, id));
  }

  /**
   * 题内容点查：question:{id}（含 latestAttemptId 指针；回答本体另走 attempt:{id}）
   */
  public QuestionDetailDto questionById(Long userId, Long id) {
    return pointAside(questionByIdKey(id), userId, QuestionDetailDto::userId,
        new TypeReference<QuestionDetailDto>() {
        }, () -> loadQuestionDetail(userId, id));
  }

  /**
   * 作答行点查：attempt:{id}（批改更新后必须 evict）
   */
  public Attempt attemptById(Long userId, Long id) {
    return pointAside(attemptByIdKey(id), userId, Attempt::getUserId,
        new TypeReference<Attempt>() {
        }, () -> attemptMapper.selectByIdScoped(id, userId));
  }

  /**
   * 该练习最新卷 id（latest_paper:{practiceId}，value=卷 id 字符串）。
   * 出新卷时写入（savePaper 插入后）；miss → 查非刷题卷 max(id) 回填。
   * 键无 userId：跨用户安全由调用端点自身的 scoped 查询兜底（脏值最多 404 回退直查）。
   */
  public Long latestPaperId(Long userId, Long practiceId) {
    String key = latestPaperKey(practiceId);
    String val = getQuiet(key);
    if (val != null) {
      try {
        return Long.parseLong(val.trim());
      } catch (NumberFormatException e) {
        delQuiet(key);   // 坏值当 miss
      }
    }
    try {
      Long paperId = paperMapper.selectLatestIdByPractice(userId, practiceId);
      if (paperId != null) setQuiet(key, String.valueOf(paperId));
      return paperId;
    } catch (DataAccessException e) {
      warnOnce("查询失败，本次不回填", e);
      return null;
    }
  }

  public void setLatestPaperId(Long practiceId, Long paperId) {
    if (practiceId != null && paperId != null) setQuiet(latestPaperKey(practiceId), String.valueOf(paperId));
  }

  /* ---- 点查 loader ---- */

  private PaperOutlineDto loadPaperOutline(Long userId, Long id) {
    Paper p = paperMapper.selectByIdScoped(id, userId);
    if (p == null) return null;
    List<Question> stubs = questionMapper.selectStubsByPaperId(userId, id);
    List<Long> qids = stubs.stream().map(Question::getId).toList();
    java.util.Set<Long> answered = new java.util.HashSet<>();
    if (!qids.isEmpty()) {   // 空列表短路防 IN ()
      // 超长自动分段（保险；单卷题量实则远低于拆条阈值）
      for (Attempt a : MappingsUtil.queryInChunks(qids,
          (chunk) -> attemptMapper.selectScoresByQuestionIds(userId, chunk))) {
        answered.add(a.getQuestionId());   // 有作答行 = 已答
      }
    }
    List<QuestionStubDto> stubDtos = new ArrayList<>();
    for (Question q : stubs) {
      stubDtos.add(new QuestionStubDto(MappingsUtil.id(q.getId()), MappingsUtil.id(q.getPaperId()),
          q.getSequence(), q.getQtype(), q.getTier(), q.getQuestionType(), q.getPoints(),
          answered.contains(q.getId()), q.getRevealedAt() != null));
    }
    return new PaperOutlineDto(userId, MappingsUtil.id(p.getId()), MappingsUtil.id(p.getPracticeId()),
        p.getSequence(), p.getKind(), p.getStatus(), p.getCompositionMode(),
        p.getMaxScore(), p.getCreatedAt(), p.getSubmittedAt(),
        MappingsUtil.parseJson(p.getSummaryJson()), MappingsUtil.parseJson(p.getProgressJson()),
        stubDtos);
  }

  private QuestionDetailDto loadQuestionDetail(Long userId, Long id) {
    Question q = questionMapper.selectByIdScoped(id, userId);
    if (q == null) return null;
    List<Attempt> ats = attemptMapper.selectByQuestionSeqAsc(userId, id);
    Long latestAttemptId = ats.isEmpty() ? null : ats.get(ats.size() - 1).getId();
    return toQuestionDetail(userId, q, latestAttemptId);
  }

  /**
   * Question 实体 → VO（缓存写穿与回源共用）
   */
  private QuestionDetailDto toQuestionDetail(Long userId, Question q, Long latestAttemptId) {
    // 问点抽取口径与 MappingsUtil.question 一致（exam 简答/代码以落库 demands 为准，客观题无清单）
    List<String> demands;
    if (q.getQtype() == null) {
      demands = com.review.vt.service.PracticeService.extractAsks(q.getPrompt());
    } else if ("short".equals(q.getQtype()) || "code".equals(q.getQtype())) {
      List<String> stored = MappingsUtil.parseStringList(q.getDemandsJson());
      demands = stored.isEmpty() ? com.review.vt.service.PracticeService.extractAsks(q.getPrompt()) : stored;
    } else {
      demands = List.of();
    }
    return new QuestionDetailDto(userId,
        MappingsUtil.id(q.getId()), MappingsUtil.id(q.getPaperId()), q.getSequence(),
        q.getPrompt(), q.getReferenceAnswer(),
        MappingsUtil.parseStringList(q.getAssignedPointsJson()), q.getAngle(),
        MappingsUtil.parseStringList(q.getKnowledgePointsJson()), demands,
        q.getTier(), q.getQuestionType(), q.getPoints(),
        q.getQtype(), q.getOptionsJson(), q.getAnswerJson(),
        q.getCreatedAt(), q.getRevealedAt(), latestAttemptId);
  }

  /**
   * 练习的卷大纲列表：卷行直查 DB 取 id（卷多、变动频繁，不建 paper_ids 索引——
   * ids 索引最深只到练习），卷大纲本身走 paper:{id} 点查缓存。
   */
  public List<PaperOutlineDto> paperOutlinesOfPractice(Long userId, Long practiceId) {
    List<Paper> rows;
    try {
      rows = paperMapper.selectByPracticeSeqAsc(userId, practiceId);
    } catch (DataAccessException e) {
      warnOnce("查询失败，本次不回填", e);
      return List.of();
    }
    List<PaperOutlineDto> out = new ArrayList<>();
    for (Paper p : rows) {
      if ("drill".equals(p.getKind())) continue;   // 刷题卷不占卷号（sequence=0），不算卷
      PaperOutlineDto o = paperById(userId, p.getId());
      if (o != null) out.add(o);
    }
    return out;
  }

  /**
   * 练习的知识点列表（两级缓存，用户指定口径）：
   * knowledge_point_ids:{practiceId} 存 id 列表，knowledge_point:{id} 存每条。
   * 冷启动/ids 过期 → 视为整组过期，一条 SQL 查全部并回填两级（页面只见这一条 SQL）；
   * ids 命中 → 逐条点查（单条缺失走 question:{id} 同款回源；查无则视为已删，不再出现）。
   */
  public List<KnowledgePoint> knowledgePointsOfPractice(Long userId, Long practiceId) {
    String idsKey = knowledgePointIdsKey(practiceId);
    List<Long> ids = parseIds(getQuiet(idsKey));
    if (ids == null) {
      // ids 缺失/坏值：完全重新初始化（一条 SQL）
      List<KnowledgePoint> rows;
      try {
        rows = knowledgePointMapper.selectByPracticeAsc(userId, practiceId);
      } catch (DataAccessException e) {
        warnOnce("查询失败，本次不回填", e);
        return List.of();
      }
      List<Long> freshIds = new ArrayList<>();
      for (KnowledgePoint kp : rows) {
        freshIds.add(kp.getId());
        setQuiet(knowledgePointByIdKey(kp.getId()), toJson(kp));
      }
      setQuiet(idsKey, toJson(freshIds));
      return rows;
    }
    List<KnowledgePoint> out = new ArrayList<>();
    for (Long id : ids) {
      KnowledgePoint kp = knowledgePointById(userId, id);
      if (kp != null) out.add(kp);
    }
    return out;
  }

  /**
   * 单条知识点点查：knowledge_point:{id}（行内 userId 校验；缺失回源查库并回填）
   */
  public KnowledgePoint knowledgePointById(Long userId, Long id) {
    return pointAside(knowledgePointByIdKey(id), userId, KnowledgePoint::getUserId,
        new TypeReference<KnowledgePoint>() {
        }, () -> knowledgePointMapper.selectByIdScoped(id, userId));
  }

  /**
   * 练习的复习文档行（0..1；无 → null，不缓存哨兵，miss 时查库）。点查键 review_doc:{practiceId}
   */
  public ReviewDoc reviewDocByPractice(Long userId, Long practiceId) {
    return pointAside(reviewDocKey(practiceId), userId, ReviewDoc::getUserId,
        new TypeReference<ReviewDoc>() {
        }, () -> reviewDocMapper.selectByPracticeScoped(userId, practiceId));
  }


  /**
   * 文档的知识卡列表（两级缓存）：review_card_ids:{reviewDocId} + review_card:{id}，与知识点同款
   */
  public List<ReviewCard> reviewCardsOfDoc(Long userId, Long reviewDocId) {
    String idsKey = reviewCardIdsKey(reviewDocId);
    List<Long> ids = parseIds(getQuiet(idsKey));
    if (ids == null) {
      List<ReviewCard> rows;
      try {
        rows = reviewCardMapper.selectByDocSeqAsc(userId, reviewDocId);
      } catch (DataAccessException e) {
        warnOnce("查询失败，本次不回填", e);
        return List.of();
      }
      List<Long> freshIds = new ArrayList<>();
      for (ReviewCard c : rows) {
        freshIds.add(c.getId());
        setQuiet(reviewCardByIdKey(c.getId()), toJson(c));
      }
      setQuiet(idsKey, toJson(freshIds));
      return rows;
    }
    List<ReviewCard> out = new ArrayList<>();
    for (Long id : ids) {
      ReviewCard c = reviewCardById(userId, id);
      if (c != null) out.add(c);
    }
    return out;
  }

  /**
   * 单条知识卡点查：review_card:{id}
   */
  public ReviewCard reviewCardById(Long userId, Long id) {
    return pointAside(reviewCardByIdKey(id), userId, ReviewCard::getUserId,
        new TypeReference<ReviewCard>() {
        }, () -> reviewCardMapper.selectByIdScoped(id, userId));
  }

  /* ==================== evict（修改前删除；尽力 DEL 不抛） ==================== */

  public void evictStage(Long id) {
    if (id != null) delQuiet(stageKey(id));
  }

  /**
   * 保存答案写穿：删旧写新（「保存答案删缓存」语义保留），读侧零回源
   */
  public void writeAttempt(Long userId, Question q, Attempt a) {
    delQuiet(questionByIdKey(q.getId()));
    setQuiet(questionByIdKey(q.getId()), toJson(toQuestionDetail(userId, q, a.getId())));
    setQuiet(attemptByIdKey(a.getId()), toJson(a));
    updatePaperStub(q.getPaperId(), q.getId(), true, null);
  }

  /**
   * 出题入库后预热：question:{id} 直接写穿（新题尚无作答，latestAttemptId=null）。
   * 答题页逐题点查首开即命中，省掉每题「查题 + 查作答」两条回源 SQL。
   * 只写单条点查键——题列表缓存（qids）是禁建的。
   */
  public void writeQuestionDetail(Long userId, Question q) {
    setQuiet(questionByIdKey(q.getId()), toJson(toQuestionDetail(userId, q, null)));
  }

  /**
   * 揭示答案写穿：question:{id} revealedAt + paper:{id} 存根 revealed
   */
  public void writeReveal(Long userId, Question q) {
    String key = questionByIdKey(q.getId());
    QuestionDetailDto old = parse(getQuiet(key), new TypeReference<QuestionDetailDto>() {
    });
    delQuiet(key);
    setQuiet(key, toJson(toQuestionDetail(userId, q, old == null ? null : old.latestAttemptId())));
    updatePaperStub(q.getPaperId(), q.getId(), null, true);
  }

  /**
   * 就地改 paper:{id} 缓存里的题存根标记（answered/revealed 任一为 null 保持原值）
   */
  private void updatePaperStub(Long paperId, Long questionId, Boolean answered, Boolean revealed) {
    String key = paperByIdKey(paperId);
    PaperOutlineDto o = parse(getQuiet(key), new TypeReference<PaperOutlineDto>() {
    });
    if (o == null) return;
    String qids = MappingsUtil.id(questionId);
    List<QuestionStubDto> stubs = new ArrayList<>();
    for (QuestionStubDto st : o.questions()) {
      if (st.id() != null && st.id().equals(qids)) {
        stubs.add(new QuestionStubDto(st.id(), st.paperId(), st.sequence(), st.qtype(), st.tier(),
            st.questionType(), st.points(),
            answered != null ? answered : st.answered(),
            revealed != null ? revealed : st.revealed()));
      } else {
        stubs.add(st);
      }
    }
    delQuiet(key);
    setQuiet(key, toJson(new PaperOutlineDto(o.userId(), o.id(), o.practiceId(), o.sequence(), o.kind(),
        o.status(), o.compositionMode(), o.maxScore(), o.createdAt(), o.submittedAt(),
        o.summary(), o.progress(), stubs)));
  }

  public void evictPracticeById(Long id) {
    if (id != null) delQuiet(practiceByIdKey(id));
  }

  public void evictPaperById(Long id) {
    if (id != null) delQuiet(paperByIdKey(id));
  }

  public void evictQuestionById(Long id) {
    if (id != null) delQuiet(questionByIdKey(id));
  }

  public void evictAttemptById(Long id) {
    if (id != null) delQuiet(attemptByIdKey(id));
  }

  public void evictLatestPaper(Long practiceId) {
    if (practiceId != null) delQuiet(latestPaperKey(practiceId));
  }

  public void evictKnowledgePointById(Long id) {
    if (id != null) delQuiet(knowledgePointByIdKey(id));
  }

  public void evictKnowledgePointIds(Long practiceId) {
    if (practiceId != null) delQuiet(knowledgePointIdsKey(practiceId));
  }

  public void evictReviewDoc(Long practiceId) {
    if (practiceId != null) delQuiet(reviewDocKey(practiceId));
  }

  public void evictReviewCardIds(Long reviewDocId) {
    if (reviewDocId != null) delQuiet(reviewCardIdsKey(reviewDocId));
  }

  public void evictReviewCardById(Long id) {
    if (id != null) delQuiet(reviewCardByIdKey(id));
  }

  /**
   * 整组删卡前失效：逐条 review_card:{id} + review_card_ids:{docId}，不留孤儿点查键。
   * 调用方须在 DB 删除**之前**调（本方法要先读到卡的 id 列表）。
   */
  public void evictReviewCardsOfDoc(Long userId, Long reviewDocId) {
    List<ReviewCard> rows;
    try {
      rows = reviewCardMapper.selectByDocSeqAsc(userId, reviewDocId);
    } catch (DataAccessException e) {
      warnOnce("查询失败，本次不逐条失效", e);
      rows = List.of();
    }
    for (ReviewCard c : rows) {
      evictReviewCardById(c.getId());
    }
    evictReviewCardIds(reviewDocId);
  }

  /* ==================== warm（启动热备专用：SETNX 不覆盖并发新值） ==================== */

  public void warmKnowledgePoint(KnowledgePoint row) {
    if (row != null) warmSet(knowledgePointByIdKey(row.getId()), toJson(row));
  }

  public void warmKnowledgePointIds(Long practiceId, List<Long> ids) {
    if (practiceId != null) warmSet(knowledgePointIdsKey(practiceId), toJson(ids));
  }

  public void warmReviewDoc(ReviewDoc doc) {
    if (doc != null) warmSet(reviewDocKey(doc.getPracticeId()), toJson(doc));
  }

  public void warmReviewCard(ReviewCard row) {
    if (row != null) warmSet(reviewCardByIdKey(row.getId()), toJson(row));
  }

  public void warmReviewCardIds(Long reviewDocId, List<Long> ids) {
    if (reviewDocId != null) warmSet(reviewCardIdsKey(reviewDocId), toJson(ids));
  }

  /* ==================== 内部 ==================== */

  /* ---- Redis 低层（全部吞异常：数据缓存绝不抛） ---- */

  private String getQuiet(String key) {
    try {
      return redis.opsForValue().get(key);
    } catch (DataAccessException e) {
      warnOnce("Redis 读失败，降级直查 DB", e);
      return null;
    }
  }

  private void setQuiet(String key, String val) {
    try {
      // 随机 TTL（[min,max] 分钟内取值）：同批缓存不在同一时刻过期，防雪崩
      redis.opsForValue().set(key, val, props.randomTtlSeconds(), TimeUnit.SECONDS);
    } catch (DataAccessException e) {
      warnOnce("Redis 写失败，跳过回填", e);
    }
  }

  private void warmSet(String key, String val) {
    try {
      redis.opsForValue().setIfAbsent(key, val, props.randomTtlSeconds(), TimeUnit.SECONDS);
    } catch (DataAccessException e) {
      warnOnce("Redis 热备写失败，跳过", e);
    }
  }

  private void delQuiet(String key) {
    try {
      redis.delete(key);
    } catch (DataAccessException e) {
      warnOnce("Redis 删失败，靠 TTL 兜底", e);
    }
  }

  /* ---- JSON ---- */

  private <T> T parse(String val, TypeReference<T> type) {
    if (val == null || val.isBlank()) return null;
    try {
      return json.readValue(val, type);
    } catch (Exception e) {
      return null;   // 坏 JSON = miss（读路径会重查并覆盖）
    }
  }

  private String toJson(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      return "[]";   // 序列化失败不该发生；退化为空列表值
    }
  }

  private void warnOnce(String msg, Exception e) {
    log.warn("[data-cache] {}：{}", msg, e.getMessage());
  }
}
