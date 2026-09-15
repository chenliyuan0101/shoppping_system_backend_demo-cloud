package com.mall.trade.oms.mq;

import com.mall.common.support.MallTime;
import com.mall.trade.common.MqMessages;
import com.mall.trade.common.MqTopology;
import com.mall.trade.oms.service.StatService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import com.mall.common.support.MemberId;

/**
 * 领域事件消费者：**只做订单日统计重算**这一个副作用（P3-5 起）。
 *
 * <p>本消费者监听 {@code mall.oms.events}，与 {@code mall-user-center} 的
 * {@code mall.user.notification} 一起绑在同一条事件交换机 {@code mall.oms.event} 上
 * （topic，三个业务路由键 {@code order.paid}/{@code order.shipped}/{@code refund.settled}）。
 * 同一条事件因此<b>两个服务各收到一份</b>，各写各的表：
 * <ul>
 *   <li><b>本消费者（oms）</b>：{@code statService.refreshDay(statDate)} 重算 {@code oms_order_daily_stat}
 *       ——统计属于交易域的派生数据，"重算式 upsert"天然幂等；</li>
 *   <li><b>user-center</b>：写一条 {@code mall_user.ums_notification}（站内消息的唯一写入方）。</li>
 * </ul>
 *
 * <p><b>P3-5 为什么必须拆开（而不是继续一个消费者干两件事）</b>：
 * 通知的属主是会员域，数据已经搬进 {@code mall_user}；单体再写一遍就是第二个写入方——
 * 两边各写各的库、各写各的幂等键，最终会变成"漏一半 / 重一半"的随机数据。
 * 因此通知的写入方随队列一起迁到 user-center，这里只保留统计（表在单体库里，属主没变）。
 *
 * <p><b>为什么不是"改名复用同一个队列"</b>：队列名就是消费者边界。
 * 若 user-center 去抢单体的队列 {@code mall.oms.events}，一条消息只会被其中一个服务拿到，
 * 通知或统计会随机丢一半（扇出靠"各自声明自己的队列 + 绑同一个交换机"，见 {@code usercenter.support.MqTopology}）。
 *
 * <p>失败处理与索引同步一致：失败 → 重试队列（TTL 到期回工作队列）→ 超限进死信队列。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.mq.enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventConsumer {

    // 时区统一到 MallTime.ZONE（业务时间口径见 MallTime 注释）

    private final StatService statService;
    private final OrderEventPublisher orderEventPublisher;

    @Value("${mall.mq.event-max-retry:3}")
    private int maxRetry;

    @RabbitListener(queues = MqTopology.EVENT_QUEUE)
    public void onOrderEvent(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int retry = MqMessages.retryCount(message);

        OrderEventMessage event;
        try {
            event = MqMessages.payload(message, OrderEventMessage.class);
        } catch (Exception e) {
            log.error("领域事件无法解析，进入死信队列: {}", e.getMessage());
            orderEventPublisher.publishRawToDlq(message, maxRetry, "消息解析失败: " + e.getMessage());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            // 统计按"事件发生当天"重算；事件是近实时消费的，通常就是今天
            LocalDate statDate = MallTime.dateOf(event.atMillis());
            boolean statOk = statService.refreshDay(statDate);
            if (!statOk) {
                throw new IllegalStateException("当日统计重算失败: " + statDate);
            }
            log.info("领域事件处理完成(仅统计): type={} orderNo={} memberId={} 统计日期={}",
                    event.eventType(), event.orderNo(), event.memberId(), statDate);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("领域事件处理失败: type={} orderNo={} 原因={}", event.eventType(), event.orderNo(), e.getMessage());
            if (retry + 1 <= maxRetry) {
                orderEventPublisher.publishRetry(event, retry + 1);
            } else {
                orderEventPublisher.publishToDlq(event, retry + 1,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            channel.basicAck(deliveryTag, false);
        }
    }
}
