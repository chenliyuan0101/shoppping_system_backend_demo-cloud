package com.mall.demo.oms.mq;

import com.mall.demo.common.JsonKit;
import com.mall.demo.common.MqTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 订单超时关单消息的投递端（延迟消息，TTL 由每条消息自己携带）。
 *
 * <p>三条设计约定：
 * <ol>
 *   <li><b>事务提交后才投递</b>：{@link #publishAfterCommit} 注册 afterCommit 回调，
 *       避免"消息已发出但下单事务回滚"导致消费者对着不存在的订单做关单</li>
 *   <li><b>投递失败 fail-open</b>：RabbitMQ 抖动/未启动只打 warn 日志，**绝不让下单失败**；
 *       漏掉的消息由兜底扫描任务（{@code OrderTimeoutTask}，默认 5 分钟一轮）补上</li>
 *   <li><b>不改全局消息转换器</b>：直接发送 JSON 字节 + {@code application/json} 头，
 *       用项目自带的 Jackson 3 工具 {@link JsonKit} 编解码，避免全局转换器影响其它潜在监听器</li>
 * </ol>
 *
 * <p>开关：{@code mall.mq.enabled=false} 时本组件退化为空实现（只记 debug 日志），
 * 关单完全交给兜底扫描任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutPublisher {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 最小延迟：避免"支付超时配置为 0"或时钟偏差导致消息立即到期、抢在下单响应前关单 */
    private static final long MIN_DELAY_MS = 1_000L;

    private final RabbitTemplate rabbitTemplate;

    @Value("${mall.mq.enabled:true}")
    private boolean mqEnabled;

    /** 下单成功后调用：把"到期关单"的消息挂到延迟队列上 */
    public void publishAfterCommit(String orderNo, LocalDateTime payExpireTime) {
        if (!mqEnabled) {
            log.debug("RabbitMQ 已关闭(mall.mq.enabled=false)，跳过超时关单消息投递: {}", orderNo);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(orderNo, payExpireTime);
                }
            });
        } else {
            publish(orderNo, payExpireTime);
        }
    }

    /**
     * 直接投递到关单消费队列（跳过延迟）。
     * 用途：运维/测试需要立刻触发一次关单判定；消费端仍会校验"是否真的过期"，不会误关。
     */
    public void publishImmediately(String orderNo) {
        send(MqTopology.WORK_EXCHANGE, MqTopology.WORK_ROUTING_KEY, orderNo, null);
    }

    private void publish(String orderNo, LocalDateTime payExpireTime) {
        long delayMs = payExpireTime == null
                ? MIN_DELAY_MS
                : Math.max(MIN_DELAY_MS, Duration.between(LocalDateTime.now(), payExpireTime).toMillis());
        send(MqTopology.DELAY_EXCHANGE, MqTopology.DELAY_ROUTING_KEY, orderNo, delayMs);
    }

    private void send(String exchange, String routingKey, String orderNo, Long delayMs) {
        try {
            long delay = delayMs == null ? 0L : delayMs;
            OrderTimeoutMessage payload = new OrderTimeoutMessage(orderNo, System.currentTimeMillis() + delay, delay);
            Message message = buildMessage(payload, delayMs);
            rabbitTemplate.send(exchange, routingKey, message);
            log.debug("超时关单消息已投递: orderNo={} exchange={} delayMs={}", orderNo, exchange, delayMs);
        } catch (Exception e) {
            // fail-open：兜底扫描任务会补偿，这里只告警
            log.warn("超时关单消息投递失败(由兜底扫描任务补偿): orderNo={} 原因={}", orderNo, e.getMessage());
        }
    }

    private Message buildMessage(OrderTimeoutMessage payload, Long delayMs) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);   // 持久化：broker 重启不丢
        props.setMessageId(payload.orderNo());
        props.setTimestamp(new Date());
        if (delayMs != null) {
            props.setExpiration(String.valueOf(delayMs));        // 每条消息自己的 TTL
        }
        return new Message(JsonKit.toJson(payload).getBytes(StandardCharsets.UTF_8), props);
    }

    /** 供消费端/运维打印用的时间转换(毫秒 → 本地时间) */
    public static LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZONE);
    }
}
