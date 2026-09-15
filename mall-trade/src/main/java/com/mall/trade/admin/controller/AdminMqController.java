package com.mall.trade.admin.controller;

import com.mall.trade.admin.dto.MqDlqRequeueRequest;
import com.mall.trade.admin.dto.MqDlqRequeueVO;
import com.mall.trade.admin.dto.MqPingVO;
import com.mall.trade.admin.service.MqDeadLetterService;
import com.mall.trade.common.ApiResponse;
import com.mall.trade.common.MqTopology;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RabbitMQ 管理与自检接口（风格与 {@link AdminEsController} 一致）。
 *
 * <p>路径在 /api/admin/** 下 → 自动要求管理员登录（AdminAuthInterceptor）。
 * <p>RabbitMQ 不可用时**不抛异常**，返回 available=false + 错误摘要，便于运维一眼看出问题。
 * <p>三个队列的语义：延迟队列（消息排队等到期，正常会随时间堆积）→ 关单队列（应长期接近 0）
 * → 死信队列（应长期为 0，非 0 说明有处理失败的消息需要人工看）。
 */
@Slf4j
@Tag(name = "后台-RabbitMQ")
@RestController
@RequestMapping("/api/admin/mq")
@RequiredArgsConstructor
public class AdminMqController {

    private final AmqpAdmin amqpAdmin;
    private final MqDeadLetterService mqDeadLetterService;

    @Value("${mall.mq.enabled:true}")
    private boolean mqEnabled;

    @Value("${mall.order.timeout-scan-interval-ms:60000}")
    private long fallbackScanIntervalMs;

    @Operation(summary = "RabbitMQ 自检(队列深度/消费者数/死信数/监听器状态)")
    @GetMapping("/ping")
    public ApiResponse<MqPingVO> ping() {
        MqPingVO data = new MqPingVO();
        data.setEnabled(mqEnabled);
        data.setFallbackScanIntervalMs(fallbackScanIntervalMs);
        try {
            data.setAvailable(true);
            // 订单超时关单链路
            data.setDelayQueue(queueInfo(MqTopology.DELAY_QUEUE));
            data.setWorkQueue(queueInfo(MqTopology.WORK_QUEUE));
            data.setDeadLetterQueue(queueInfo(MqTopology.DLQ));
            // 商品索引同步链路
            data.setProductSyncQueue(queueInfo(MqTopology.SYNC_QUEUE));
            data.setProductSyncRetryQueue(queueInfo(MqTopology.SYNC_RETRY_QUEUE));
            data.setProductSyncDlq(queueInfo(MqTopology.SYNC_DLQ));
            // 领域事件链路
            data.setEventQueue(queueInfo(MqTopology.EVENT_QUEUE));
            data.setEventRetryQueue(queueInfo(MqTopology.EVENT_RETRY_QUEUE));
            data.setEventDlq(queueInfo(MqTopology.EVENT_DLQ));
        } catch (Exception e) {
            log.warn("RabbitMQ 自检失败: {}", e.getMessage());
            data.setAvailable(false);
            data.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return ApiResponse.ok(data);
    }

    @Operation(summary = "死信队列重投(先修好下游再批量放回；重投会重置重试次数)")
    @PostMapping("/dlq/requeue")
    public ApiResponse<MqDlqRequeueVO> requeueDlq(
            @RequestBody(required = false) MqDlqRequeueRequest request) {
        MqDlqRequeueRequest req = request == null ? new MqDlqRequeueRequest() : request;
        if (req.getQueue() == null || req.getQueue().isBlank()) {
            return ApiResponse.ok(mqDeadLetterService.requeueAll(req.getLimit()));
        }
        return ApiResponse.ok(mqDeadLetterService.requeue(req.getQueue(), req.getLimit()));
    }

    /** 单个队列的观测信息；队列不存在返回 exists=false（例如 mall.mq.enabled=false 时未声明） */
    private Map<String, Object> queueInfo(String name) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name);
        // Spring AMQP 4.x：getQueueInfo 返回 QueueInformation(3.x 是 Properties)
        QueueInformation queue = amqpAdmin.getQueueInfo(name);
        if (queue == null) {
            info.put("exists", false);
            return info;
        }
        info.put("exists", true);
        info.put("messages", queue.getMessageCount());
        info.put("consumers", queue.getConsumerCount());
        return info;
    }
}
