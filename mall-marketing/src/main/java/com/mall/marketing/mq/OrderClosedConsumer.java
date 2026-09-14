package com.mall.marketing.mq;

import com.mall.marketing.service.CouponCommandService;
import com.mall.marketing.support.MqMessages;
import com.mall.marketing.support.MqTopology;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 消费 {@code order.closed}，把该单锁定的券**再解一次**（P5 步骤 E 的第二道防线）。
 *
 * <h2>它在防线里的位置</h2>
 * 单体在关单事务里已经**同步**调过 {@code unlock} 了。同步调用的前提是"目标服务当时可达"——
 * 服务重启、网络抖动、超时都可能让它失败。本消费者在**事务提交后**收到事件再解一次（幂等），
 * 覆盖那些失败；第三道防线是 {@code task.CouponLockReconcileTask} 的每日对账（覆盖"事件也丢了"）。
 *
 * <h2>ack 口径：手动 ack（配置 {@code spring.rabbitmq.listener.simple.acknowledge-mode=manual}）</h2>
 * <ul>
 *   <li><b>正文解析不了</b>（字段名被改、非 JSON）→ 重投多少次都一样，直接进死信 + {@code log.error}；</li>
 *   <li><b>业务处理抛异常</b>（例如营销库暂时不可写）→ 投到<b>自己的</b>重试队列，
 *       每条消息带 TTL 等待后死信回工作队列；重试超限进死信；</li>
 *   <li><b>成功</b>（含"该单没用券"与"券本来就不需要解"）→ ack。</li>
 * </ul>
 * ⚠️ 无论哪条路径都必须 ack/reject 一次，否则消息会一直 unacked 占着 prefetch 名额。
 *
 * <h2>幂等</h2>
 * {@code unlock} 是条件 UPDATE（{@code 3 → 0} + {@code order_no} 匹配）：重复投递只会影响 0 行，
 * 不会把别的单锁的券放回去，也不会把已核销（{@code USED}）的券"复活"。
 *
 * <h2>⚠️ 不能从 {@code unlock} 的返回值判断"是不是我解的"</h2>
 * {@code unlock} 返回 {@code true} 有两种情况：<b>(a)</b>本次真的把 {@code LOCKED} 改成了 {@code UNUSED}；
 * <b>(b)</b>幂等命中——查出来这张券<b>本来就是</b> {@code UNUSED}（即关单时的同步解锁已经成功了）。
 * 两者返回值相同（这是契约，见 {@code CouponCommandService#unlock}），因此本消费者<b>只能</b>把
 * {@code true} 记为"兜底路径已确认券处于未使用"，<b>不能</b>说成"同步解锁没生效"——
 * 健康路径上必然走的是 (b)，那样写等于每次正常关单都报一条假告警。
 * （第一版这里就是 {@code log.warn("…说明关单时的同步解锁没生效")}，被步骤 E 场景①的日志证伪后改正。）
 *
 * <h2>为什么队列只绑 {@code order.closed}</h2>
 * 绑定只写了这一个业务键（见 {@code config/RabbitMqConfig}），因此本监听器**不需要**再按
 * {@code eventType} 过滤——"队列名 + 绑定"就是过滤器，漏写过滤条件这类错误在这里不可能发生。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.mq.enabled", havingValue = "true", matchIfMissing = true)
public class OrderClosedConsumer {

    private final CouponCommandService couponCommandService;
    private final RabbitTemplate rabbitTemplate;

    /** 失败重投上限（超过就进死信；与 review/user-center 同名同默认值） */
    @Value("${mall.mq.event-max-retry:3}")
    private int maxRetry;

    /** 重试延迟（投到重试队列时作为消息 TTL；与 review/user-center 同名同默认值） */
    @Value("${mall.mq.event-retry-delay-ms:10000}")
    private long retryDelayMs;

    @RabbitListener(queues = MqTopology.MARKETING_QUEUE)
    public void onOrderClosed(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int retry = MqMessages.retryCount(message);

        OrderClosedMessage payload;
        try {
            payload = MqMessages.payload(message, OrderClosedMessage.class);
        } catch (Exception e) {
            // 正文解析不了：重投无用，直接死信 + 响亮报错
            log.error("order.closed 正文无法解析，进死信: retry={} 原因={}", retry, e.getMessage());
            rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, MqTopology.MARKETING_ROUTING_DLQ,
                    MqMessages.copyOf(message, retry, 0, "parse: " + e.getMessage()));
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            handle(payload);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            int next = retry + 1;
            if (next >= maxRetry) {
                log.error("order.closed 处理失败且已达重试上限，进死信: orderNo={} retry={}/{} 原因={}",
                        payload.orderNo(), next, maxRetry, e.getMessage());
                rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, MqTopology.MARKETING_ROUTING_DLQ,
                        MqMessages.copyOf(message, next, 0, e.getMessage()));
            } else {
                log.warn("order.closed 处理失败，{}ms 后重投: orderNo={} retry={}/{} 原因={}",
                        retryDelayMs, payload.orderNo(), next, maxRetry, e.getMessage());
                rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, MqTopology.MARKETING_ROUTING_RETRY,
                        MqMessages.copyOf(message, next, retryDelayMs, e.getMessage()));
            }
            channel.basicAck(deliveryTag, false);
        }
    }

    /**
     * 业务处理：把该单锁定的券解回 {@code UNUSED}。
     *
     * <p>没用券的订单（{@code couponMemberId == null}）也会收到事件——直接什么都不做并 ack：
     * 保持"每次关单都有一条事件"的简单语义（将来若有别的消费者要接，不必回头改发布方）。
     */
    private void handle(OrderClosedMessage payload) {
        if (payload.couponMemberId() == null) {
            log.debug("order.closed: 该单未用券，无需处理 orderNo={} reason={}", payload.orderNo(), payload.reason());
            return;
        }
        boolean changed = couponCommandService.unlock(payload.memberId(), payload.couponMemberId(), payload.orderNo());
        if (changed) {
            // 注意：true 只是"券现在确实是 UNUSED"，分不清是本次解的、还是关单时的同步解锁已经解好了
            // （见类注释"不能从 unlock 的返回值判断"）。所以这里只能记 INFO，且措辞不指向"谁解的"。
            log.info("order.closed 兜底解锁已确认（券处于未使用；无法区分本次/同步解锁）: orderNo={} couponMemberId={} reason={}",
                    payload.orderNo(), payload.couponMemberId(), payload.reason());
        } else {
            // 幂等命中以外的 false：券不属于该会员 / 已被核销(USED) / 被别的单 LOCKED —— 都属正常
            log.info("order.closed 兜底解锁未确认（券不属该会员或已被核销/被他人锁定）: orderNo={} couponMemberId={} reason={}",
                    payload.orderNo(), payload.couponMemberId(), payload.reason());
        }
    }
}
