package com.review.vt.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务事件推送（SSE）：出题/批改**完成**推给前端，替代轮询刷接口。
 * 过程进度不推送——每批一推效果不好还连带每批写一次库；任务收尾推**一次** done，
 * 前端收到即整刷一次（状态翻面 + 成绩/题目回拉）。
 *
 * <p>事件是**提示不是指令**：客户端不在线、连接断开、发送失败一律静默丢弃，
 * 前端靠「刷新即重连 + 断线自动重连」兜底，后端不为丢失的事件做任何补偿查询。
 * 连接空闲时零开销（不发心跳、不查库）。
 */
@Service
public class TaskEventService {

    private static final Logger log = LoggerFactory.getLogger(TaskEventService.class);

    /** practiceId → 订阅连接 */
    private final Map<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /** 订阅某练习的任务事件。不设超时：任务可能跑几分钟；断开自动摘除。 */
    public SseEmitter subscribe(Long practiceId) {
        SseEmitter emitter = new SseEmitter(0L);
        Set<SseEmitter> set = subscribers.computeIfAbsent(practiceId, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        // 摘连接；顺手清掉空掉的 practice 键，避免订阅表只增不减
        Runnable bye = () -> {
            set.remove(emitter);
            removeIfEmpty(practiceId, set);
        };
        emitter.onCompletion(bye);
        emitter.onTimeout(bye);
        emitter.onError(e -> bye.run());
        // 立刻冲一次响应头：SseEmitter 默认等首个事件才提交，客户端得先拿到流
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception e) {
            bye.run();
        }
        return emitter;
    }

    /**
     * 推送一条事件：目前只有 done（任务收尾，全任务唯一一条）。
     * payload 的 message（如「全部出完」）给前端提示；发送失败只摘连接，绝不影响后台任务本身。
     *
     * 整个方法**永不抛出**——调用方（doGenerate/doGrade 的 finally）还要做任务收尾，
     * 这里抛出会连累 running 清理，任务会被永久锁死。
     */
    public void publish(Long practiceId, String type, Long paperId, String stage,
                        boolean running, int planned, int done, String message) {
        try {
            // ==== 步骤 1：组装事件 JSON ====
            ObjectNode node = com.review.vt.common.util.MappingsUtil.MAPPER.createObjectNode();
            node.put("type", type);
            if (paperId != null) node.put("paperId", paperId);
            node.put("stage", stage == null ? "" : stage);
            node.put("running", running);
            node.put("planned", planned);
            node.put("done", done);
            if (message != null && !message.isBlank()) node.put("message", message);
            String data = node.toString();

            // ==== 步骤 2：逐连接发送，失败只摘自己 ====
            Set<SseEmitter> set = subscribers.get(practiceId);
            if (set == null || set.isEmpty()) return;
            for (SseEmitter em : set) {
                try {
                    em.send(SseEmitter.event().data(data));
                } catch (Exception e) {
                    log.debug("SSE 发送失败，摘除连接：{}", e.getMessage());
                    set.remove(em);
                }
            }
            removeIfEmpty(practiceId, set);
        } catch (Exception e) {
            log.warn("SSE 事件推送异常，忽略：{}", e.getMessage());
        }
    }

    /** 空集合的 practice 键从订阅表移除（两参 remove，只删自己挂的那个集合） */
    private void removeIfEmpty(Long practiceId, Set<SseEmitter> set) {
        if (set.isEmpty()) {
            subscribers.remove(practiceId, set);
        }
    }
}
