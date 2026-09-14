package com.mall.usercenter.mq;

import com.mall.usercenter.support.MqMessages;
import com.mall.usercenter.support.MqTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 通知事件的<b>失败重投端</b>（重试队列 / 死信队列），单体 {@code oms.mq.OrderEventPublisher}
 * 里那部分逻辑的契约副本。
 *
 * <p>为什么消费端还要会"投"：单体把重试做成"失败 → 投重试队列（消息自带 TTL）→ 到期死信回工作队列"，
 * 投递动作原本住在发布者那一侧（生产者与消费者同一个进程）。现在消费者独立成服务，
 * 这套投递必须跟着消费者走——否则失败消息无处可去。键名是本服务自己的（见
 * {@link MqTopology#NOTIFICATION_ROUTING_RETRY}），不会和单体的重试/死信键串味。
 *
 * <p>两点与单体一致的约定：**字段/头名逐字相同**（{@link MqMessages}）、**fail-open**——
 * 连重投本身都失败时只告警（消息已被 ack，不会毒住队列；运维可从日志定位业务单号人工补投）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.mq.enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventRetryPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${mall.mq.event-retry-delay-ms:10000}")
    private long eventRetryDelayMs;

    /** 重试：带上重试次数投到重试队列(TTL 到期回工作队列) */
    public void publishRetry(OrderEventMessage event, int retry) {
        try {
            Message message = MqMessages.json(event, retryDelayMs(), event.orderNo(),
                    Map.of(MqMessages.HEADER_RETRY, retry));
            rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, MqTopology.NOTIFICATION_ROUTING_RETRY, message);
            log.warn("站内消息写入失败，已投重试队列: type={} orderNo={} 重试={}",
                    event.eventType(), event.orderNo(), retry);
        } catch (Exception e) {
            log.error("站内消息重投失败(不影响其它消息): type={} orderNo={} 原因={}",
                    event.eventType(), event.orderNo(), e.getMessage());
        }
    }

    /** 重试超限 → 死信队列 */
    public void publishToDlq(OrderEventMessage event, int retry, String reason) {
        try {
            Message message = MqMessages.json(event, null, event.orderNo(), Map.of(
                    MqMessages.HEADER_RETRY, retry, MqMessages.HEADER_FAIL_REASON, reason));
            rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, MqTopology.NOTIFICATION_ROUTING_DLQ, message);
            log.error("站内消息进入死信队列: type={} orderNo={} 重试={} 原因={}",
                    event.eventType(), event.orderNo(), retry, reason);
        } catch (Exception e) {
            log.error("站内消息进死信失败: {}", e.getMessage());
        }
    }

    /** 正文无法解析的消息原样进死信（保留原始字节，人工可读） */
    public void publishRawToDlq(Message original, int retry, String reason) {
        try {
            rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, MqTopology.NOTIFICATION_ROUTING_DLQ,
                    MqMessages.copyOf(original, retry, 0, reason));
            log.error("站内消息(无法解析)进入死信队列: {}", reason);
        } catch (Exception e) {
            log.error("站内消息进死信失败: {}", e.getMessage());
        }
    }

    private int retryDelayMs() {
        return (int) Math.max(1, eventRetryDelayMs);
    }
}
