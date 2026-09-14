package com.mall.demo.oms.mq;

import com.mall.demo.common.MqMessages;
import com.mall.demo.common.MqTopology;
import com.mall.demo.oms.outbox.TradeOutboxRelay;
import com.mall.demo.oms.outbox.TradeOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * 领域事件投递端（支付成功 / 已发货 / 退款到账 / 确认收货 / 关单）。
 *
 * <p><b>P8-3 起：先入发件箱，再投递</b>（本地消息表 / transactional outbox）。
 * 与改造前的差别只有一处，但很关键：**事件在业务事务里落库**，于是
 * "进程在投递前崩掉 ⇒ 事件永久丢失"这个缺陷被消除；投递失败/进程崩溃的行由
 * {@link com.mall.demo.oms.outbox.TradeOutboxRelay} 定时重投（至少一次语义）。
 *
 * <p>仍然保留的两条既有约定：
 * <ul>
 *   <li><b>事务提交后才投递</b>：避免"事件已发、业务回滚"（现在是"行跟着事务回滚 + 提交后才发"）。</li>
 *   <li><b>fail-open</b>：事件发不出去只告警，不能影响支付/发货/退款本身。**行留在箱里**等重投，
 *       不再像改造前那样"丢了就没了"。</li>
 * </ul>
 *
 * <p>⚠️ 消费侧现在是**至少一次**投递（可能重复）：幂等由消费方负责
 * （按日统计是重算式、天然幂等；通知按业务键可查）。这条口径写进了 P8-3 报告。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final TradeOutboxService outbox;
    private final TradeOutboxRelay outboxRelay;

    @Value("${mall.mq.enabled:true}")
    private boolean mqEnabled;

    @Value("${mall.mq.event-retry-delay-ms:10000}")
    private long eventRetryDelayMs;

    /** 发件箱总开关（应急时置 false ⇒ 退回"提交后直投"的老路径，用于对照排障） */
    @Value("${mall.mq.outbox.enabled:true}")
    private boolean outboxEnabledFlag;

    /** 业务事务提交后发事件；不在事务里则直接发 */
    public void publishAfterCommit(OrderEventMessage event) {
        publishDurable(event.eventType(), routingKey(event.eventType()), event.orderNo(), event);
    }

    /**
     * 业务事务提交后发一条**自定义载荷**的事件（P4：order.finished / 关单）。
     *
     * <p>为什么不复用 {@link #publishAfterCommit(OrderEventMessage)}：那个方法把载荷类型写死成
     * {@code OrderEventMessage}，而 order.finished 要带 items 明细快照；为它改动共用形状，
     * 会直接影响既有三条事件与 user-center 通知消费者的反序列化契约（一次跨服务的隐性破坏）。
     * 因此这里**新增**一条投递路径，既有三个方法一行未动。
     *
     * @param routingKey 路由键（{@code MqTopology.EVENT_ROUTING_*}）
     * @param messageId  消息 id（用业务单号，便于排查）
     * @param payload    任意可序列化载荷（与既有事件一样走 {@code MqMessages.json} 手工构造 Message）
     */
    public void publishRawAfterCommit(String routingKey, String messageId, Object payload) {
        publishDurable(routingKey, routingKey, messageId, payload);
    }

    /**
     * **P8-3 的主路径**：同一事务入箱 → 提交后走快路径立刻投递（默认）→ 失败或崩了由定时重投补发。
     *
     * <p>三步的顺序与理由：
     * <ol>
     *   <li>{@code mqEnabled=false} ⇒ 完全按老行为直接返回（连箱都不落）：这是**测试与本地关 MQ** 的口径，
     *       也是 P8 之前的行为，保持逐字不变（很多切片测试依赖它）。</li>
     *   <li>{@code outbox.enabled=false} ⇒ 退回"提交后直投"的老路径（应急开关，用于对照排障）。</li>
     *   <li>入箱发生在**当前事务**里；之后的投递是"提交后"的动作 —— 于是回滚不会发出事件、崩溃不会丢事件。</li>
     * </ol>
     */
    private void publishDurable(String eventType, String routingKey, String bizKey, Object payload) {
        if (!mqEnabled) {
            log.debug("RabbitMQ 已关闭(mall.mq.enabled=false)，跳过领域事件: {}", routingKey);
            return;
        }
        if (!outboxEnabledFlag) {
            sendRaw(routingKey, bizKey, payload);
            return;
        }
        // ① 入箱：跟随调用方事务（无事务时自动提交，同样安全）
        Long id = outbox.enqueue(eventType, routingKey, bizKey, payload).getId();
        // ② 提交后投递（快路径）。即使这条回调因为"嵌套 afterCommit"没被触发，
        //    行已经在库里 ⇒ 定时重投会补发（这正是发件箱比 afterCommit 直投更可靠的地方）。
        if (!outboxRelay.fastPathEnabled()) {
            log.debug("发件箱快路径关闭，等定时重投: id={} routingKey={} bizKey={}", id, routingKey, bizKey);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    outboxRelay.sendOne(id);
                }
            });
        } else {
            outboxRelay.sendOne(id);
        }
    }

    private void sendRaw(String routingKey, String messageId, Object payload) {
        try {
            rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, routingKey,
                    MqMessages.json(payload, null, messageId, Map.of()));
            log.info("领域事件已投递(直投路径): routingKey={} messageId={}", routingKey, messageId);
        } catch (Exception e) {
            log.error("领域事件投递失败: routingKey={} messageId={} 原因={}", routingKey, messageId, e.getMessage());
        }
    }
    /** 重试：带上重试次数投到重试队列(TTL 到期回工作队列) */
    public void publishRetry(OrderEventMessage event, int retry) {
        send(event, MqTopology.EVENT_ROUTING_RETRY, retry);
    }

    /** 重试超限 → 死信队列 */
    public void publishToDlq(OrderEventMessage event, int retry, String reason) {
        try {
            Message message = MqMessages.json(event, null, event.orderNo(), Map.of(
                    MqMessages.HEADER_RETRY, retry, MqMessages.HEADER_FAIL_REASON, reason));
            rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, MqTopology.EVENT_ROUTING_DLQ, message);
            log.error("领域事件进入死信队列: type={} orderNo={} 重试={} 原因={}",
                    event.eventType(), event.orderNo(), retry, reason);
        } catch (Exception e) {
            log.error("领域事件进死信失败: {}", e.getMessage());
        }
    }

    /** 正文无法解析的消息原样进死信 */
    public void publishRawToDlq(Message original, int retry, String reason) {
        try {
            rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, MqTopology.EVENT_ROUTING_DLQ,
                    MqMessages.copyOf(original, retry, 0, reason));
            log.error("领域事件消息(无法解析)进入死信队列: {}", reason);
        } catch (Exception e) {
            log.error("领域事件进死信失败: {}", e.getMessage());
        }
    }

    public static String routingKey(String eventType) {
        return switch (eventType) {
            case OrderEventMessage.TYPE_ORDER_PAID -> MqTopology.EVENT_ROUTING_PAID;
            case OrderEventMessage.TYPE_ORDER_SHIPPED -> MqTopology.EVENT_ROUTING_SHIPPED;
            case OrderEventMessage.TYPE_REFUND_SETTLED -> MqTopology.EVENT_ROUTING_REFUND;
            // P4：确认收货事件（载荷是 OrderFinishedMessage，但路由键推导仍走同一张表）
            case OrderFinishedMessage.TYPE_ORDER_FINISHED -> MqTopology.EVENT_ROUTING_FINISHED;
            default -> MqTopology.EVENT_ROUTING_REDELIVER;
        };
    }

    private void send(OrderEventMessage event, String routingKey) {
        send(event, routingKey, 0);
    }

    private void send(OrderEventMessage event, String routingKey, int retry) {
        try {
            Integer ttl = retry > 0 ? retryDelayMs() : null;
            Message message = MqMessages.json(event, ttl, event.orderNo(),
                    retry > 0 ? Map.of(MqMessages.HEADER_RETRY, retry) : null);
            rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, routingKey, message);
            log.debug("领域事件已投递: type={} orderNo={} routingKey={} retry={}",
                    event.eventType(), event.orderNo(), routingKey, retry);
        } catch (Exception e) {
            log.warn("领域事件投递失败(不影响业务): type={} orderNo={} 原因={}",
                    event.eventType(), event.orderNo(), e.getMessage());
        }
    }

    private int retryDelayMs() {
        return (int) Math.max(1, eventRetryDelayMs);
    }
}
