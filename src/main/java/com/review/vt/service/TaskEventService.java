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
 * 任务事件推送（SSE）：出题/批改的进度与完成推给前端，替代轮询刷接口。
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
        Runnable bye = () -> set.remove(emitter);
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
     * 推送一条事件：type=progress（一批完成）/ done（任务收尾）。
     * payload 带数量（planned/done）与 message（如「全部出完」），前端据此直接渲染进度，不必回拉全量。
     * 发送失败只摘连接，绝不影响后台任务本身。
     */
    public void publish(Long practiceId, String type, Long paperId, String stage,
                        boolean running, int planned, int done, String message) {
        Set<SseEmitter> set = subscribers.get(practiceId);
        if (set == null || set.isEmpty()) return;
        ObjectNode node = com.review.vt.common.util.MappingsUtil.MAPPER.createObjectNode();
        node.put("type", type);
        if (paperId != null) node.put("paperId", paperId);
        node.put("stage", stage == null ? "" : stage);
        node.put("running", running);
        node.put("planned", planned);
        node.put("done", done);
        if (message != null && !message.isBlank()) node.put("message", message);
        String data = node.toString();
        for (SseEmitter em : set) {
            try {
                em.send(SseEmitter.event().data(data));
            } catch (Exception e) {
                log.debug("SSE 发送失败，摘除连接：{}", e.getMessage());
                set.remove(em);
            }
        }
    }
}
