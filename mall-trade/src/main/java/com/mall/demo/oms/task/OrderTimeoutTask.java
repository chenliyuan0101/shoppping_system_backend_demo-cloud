package com.mall.demo.oms.task;

import com.mall.demo.oms.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订单超时关单的**兜底扫描**任务。
 *
 * <p>为什么还要它：关单的第一手段已经是 RabbitMQ 延迟消息（{@code OrderTimeoutPublisher} →
 * {@code OrderTimeoutConsumer}），但消息链路存在几处"可能漏"的窗口：
 * <ul>
 *   <li>投递时 RabbitMQ 不可用（投递是 fail-open，只告警不阻断下单）</li>
 *   <li>服务停机期间消息虽在队列里，恢复后才会被消费（本身没问题，但堆积时更慢）</li>
 *   <li>消息被 nack 进死信队列、或人为清了队列</li>
 * </ul>
 * 因此保留这个低频扫描任务作为保险：{@code mall.order.timeout-scan-interval-ms} 默认 5 分钟；
 * 若把 {@code mall.mq.enabled} 置为 false（不用 MQ），应把它调回 60000（1 分钟），
 * 否则关单精度就变成 5 分钟。
 *
 * <p>与消费者共用 {@code OrderService#closeIfExpired} 同一段幂等逻辑，重复执行安全。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutTask {

    private final OrderService orderService;

    @Scheduled(fixedDelayString = "${mall.order.timeout-scan-interval-ms:60000}", initialDelay = 30_000)
    public void closeExpired() {
        try {
            orderService.closeExpiredOrders();
        } catch (Exception e) {
            log.error("超时关单兜底扫描执行失败", e);
        }
    }
}
