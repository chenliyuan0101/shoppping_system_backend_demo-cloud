package com.mall.trade.oms.mq;

import com.mall.common.support.JsonKit;
import com.mall.trade.common.MqTopology;
import com.mall.trade.oms.service.OrderService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 订单超时关单消费者：延迟消息到期后由 broker 投递到 {@link MqTopology#WORK_QUEUE}，
 * 这里回库校验订单状态并关单/回补库存。
 *
 * <p>可靠性约定（{@code acknowledge-mode: manual}）：
 * <ul>
 *   <li>处理成功 → {@code basicAck}</li>
 *   <li>处理抛异常(数据库抖动/毒消息) → {@code basicNack(requeue=false)} 进死信队列，
 *       <b>不重回队列</b>，避免坏消息把消费者打成死循环；这些订单仍会被兜底扫描任务处理</li>
 * </ul>
 *
 * <p>幂等：{@code OrderService#closeIfExpired} 只关"待支付且已过期"的订单，
 * 因此消息重复投递、支付成功后又收到消息、兜底扫描与消费者同时命中，都是安全的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.mq.enabled", havingValue = "true", matchIfMissing = true)
public class OrderTimeoutConsumer {

    private final OrderService orderService;

    @RabbitListener(queues = MqTopology.WORK_QUEUE)
    public void onOrderTimeout(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String orderNo = "?";
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            OrderTimeoutMessage payload = JsonKit.toObject(body, OrderTimeoutMessage.class);
            orderNo = payload.orderNo();
            boolean closed = orderService.closeIfExpired(orderNo);
            if (closed) {
                log.info("MQ 超时关单完成: orderNo={} 到期时间={} 延迟={}ms",
                        orderNo, OrderTimeoutPublisher.toLocalDateTime(payload.payExpireTimeMillis()),
                        payload.delayMillis());
            } else {
                log.debug("MQ 超时关单跳过(订单已支付/已取消/未到期): orderNo={}", orderNo);
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("MQ 超时关单处理失败，转入死信队列: orderNo={} 原因={}", orderNo, e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
