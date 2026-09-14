package com.mall.usercenter.mq;

import com.mall.usercenter.service.NotificationService;
import com.mall.usercenter.support.MqMessages;
import com.mall.usercenter.support.MqTopology;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 站内消息的领域事件消费者：<b>P3-5 起本服务是 ums_notification 的唯一写入方</b>。
 *
 * <p>监听自己的队列 {@link MqTopology#NOTIFICATION_QUEUE}（{@code mall.user.notification}），
 * 该队列与单体的 {@code mall.oms.events} 一起绑在同一条事件交换机 {@code mall.oms.event} 上，
 * 三个业务路由键 {@code order.paid} / {@code order.shipped} / {@code refund.settled} 照抄。
 * 于是同一条事件<b>两个服务各收到一份</b>：单体只重算订单日统计，本服务只写站内消息（一个副作用一个属主）。
 *
 * <h2>为什么是独立队列而不是复用单体的队列名</h2>
 * 同名队列 = 竞争消费：一条消息只会被其中一个服务拿到，通知与统计会随机各丢一半。
 * 队列名就是消费者边界，见 {@link MqTopology} 的类注释。
 *
 * <h2>幂等：靠数据库唯一键，不靠"只消费一次"</h2>
 * MQ 的投递语义是 at-least-once，重复投递是常态（重试、重投、broker 重启）。
 * 这里对重复投递<b>不做任何应用层判断</b>——{@code ums_notification} 的唯一键
 * {@code (member_id, type, biz_no)} + {@code ON DUPLICATE KEY UPDATE id = id} 才是唯一防线
 * （先查后插在并发重复投递下会撞唯一键报错）。因此在重复投递时本条日志会显示"已存在"，这是正常的。
 *
 * <h2>文案：与单体逐字一致</h2>
 * 标题/正文/业务单号的拼接逻辑是从单体 {@code oms.mq.OrderEventConsumer#pushNotification} 原样搬过来的，
 * 一个字都没改（前端与测试都在断言这些文案）。金额用整数运算拼"分 → 元"，避免浮点误差。
 *
 * <h2>P4-2：不处理的事件类型要"显式沉默"，不能靠 default 刷 WARN</h2>
 * trade 在 P4-2 新增了确认收货事件 {@link OrderEventMessage#TYPE_ORDER_FINISHED}（评价域用它建待评价读模型）。
 * 本服务对它<b>没有副作用</b>，但 {@link #pushNotification} 里的 {@code switch} 若只靠 {@code default}
 * 兜底，就会为<b>每一条</b>该类型的事件打一条"未知事件类型" WARN —— 日志被噪声淹没，
 * 真正的未知类型反而看不见。因此这里给它一个<b>显式</b>的 no-op 分支（debug 日志），
 * {@code default} 仍然只留给真正未知的类型；既有三条事件的文案与行为一个字都没动
 * （由 {@code NotificationEventConsumerTest} 用 mock 逐字守着）。
 *
 * <p>⚠️ 实测（P4-2 施工时用本项目的 Jackson 3.0.2 直接验证过，结论与计划文档的假设不同）：
 * {@code order.finished} 的<b>真实正文</b>（{@code {orderNo, memberId, finishedTime, items[]}}，
 * 没有 eventType 字段）<b>根本进不了这个 switch</b>——Jackson 3 默认开启
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES}，缺失的 primitive {@code atMillis} 让反序列化直接抛
 * {@code MismatchedInputException}，于是在 {@link #onOrderEvent} 里走的是"无法解析 → 死信队列"
 * 那条分支（ERROR + DLQ），而不是这里的 default WARN。
 * 也就是说：本批要防的那条 WARN 只会在有人用 {@code OrderEventMessage} 的形状发 ORDER_FINISHED
 * （例如单体 {@code OrderEventPublisher.publishAfterCommit(new OrderEventMessage(TYPE_ORDER_FINISHED, ...))}）时出现
 * —— 显式分支正好覆盖它；而"真形状"事件若被误绑到本服务的队列，现象是<b>响亮</b>的死信堆积，
 * 该做的是把绑定去掉，不是把日志降级（那种情况必须留痕）。
 *
 * <h2>失败处理</h2>
 * 手动 ack（{@code spring.rabbitmq.listener.simple.acknowledge-mode=manual}）：
 * 成功 ack；失败 → 重试队列（消息自带 TTL，到期回本队列）；重试超限或正文解析失败 → 死信队列。
 * 三种情况都 ack 原始消息，避免毒消息无限重入（重投入口见 {@link OrderEventRetryPublisher}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.mq.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationEventConsumer {

    private final NotificationService notificationService;
    private final OrderEventRetryPublisher orderEventRetryPublisher;

    @Value("${mall.mq.event-max-retry:3}")
    private int maxRetry;

    @RabbitListener(queues = MqTopology.NOTIFICATION_QUEUE)
    public void onOrderEvent(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int retry = MqMessages.retryCount(message);

        OrderEventMessage event;
        try {
            event = MqMessages.payload(message, OrderEventMessage.class);
        } catch (Exception e) {
            log.error("领域事件无法解析，进入死信队列: {}", e.getMessage());
            orderEventRetryPublisher.publishRawToDlq(message, maxRetry, "消息解析失败: " + e.getMessage());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            boolean notified = pushNotification(event);
            log.info("领域事件通知处理完成: type={} orderNo={} memberId={} 站内消息={}",
                    event.eventType(), event.orderNo(), event.memberId(), notified ? "新增" : "已存在");
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("站内消息写入失败: type={} orderNo={} 原因={}", event.eventType(), event.orderNo(), e.getMessage());
            if (retry + 1 <= maxRetry) {
                orderEventRetryPublisher.publishRetry(event, retry + 1);
            } else {
                orderEventRetryPublisher.publishToDlq(event, retry + 1,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            channel.basicAck(deliveryTag, false);
        }
    }

    /** 按事件类型生成站内消息文案；幂等由唯一键保证（文案与单体逐字一致，勿改） */
    private boolean pushNotification(OrderEventMessage event) {
        if (event.memberId() == null) {
            return false;
        }
        String eventType = event.eventType();
        String title;
        String content;
        switch (eventType) {
            case OrderEventMessage.TYPE_ORDER_PAID -> {
                title = "支付成功";
                content = "订单 " + event.orderNo() + " 已支付成功，实付 " + yuan(event.amount()) + " 元，我们会尽快为你发货。";
            }
            case OrderEventMessage.TYPE_ORDER_SHIPPED -> {
                title = "商品已发货";
                content = "订单 " + event.orderNo() + " 已发货"
                        + (event.remark() == null || event.remark().isBlank() ? "。" : "，物流：" + event.remark() + "。")
                        + "请留意收货。";
            }
            case OrderEventMessage.TYPE_REFUND_SETTLED -> {
                title = "退款已到账";
                content = "订单 " + event.orderNo() + " 的退款 " + yuan(event.amount()) + " 元已处理完成，请查收。";
            }
            case OrderEventMessage.TYPE_ORDER_FINISHED -> {
                // P4-2：**显式**的"收到了但不处理"。确认收货没有站内消息类型（前端也没有这个文案），
                // 消费端只 ack。写成显式分支而不是让它掉进 default，是因为 default 的 WARN 是留给
                // 真正未知类型的观测信号——按订单量刷 WARN 会把那个信号淹掉。
                // 实测能走到这里的形状：完整的 OrderEventMessage 正文（六个字段齐全）且 eventType 为
                // ORDER_FINISHED；order.finished 的"另一种形状"（无 eventType）根本解析不到这里，
                // 见类注释里的实测结论。
                log.debug("确认收货事件不生成站内消息(本服务对该类型无副作用): orderNo={}", event.orderNo());
                return false;
            }
            default -> {
                log.warn("未知事件类型，不生成站内消息: {}", eventType);
                return false;
            }
        }
        // 落库的 type 用的是事件类型本身（ORDER_PAID / ORDER_SHIPPED / REFUND_SETTLED），与改造前一致
        return notificationService.push(event.memberId(), eventType, title, content, event.orderNo());
    }

    /** 分 → 元（保留两位，避免浮点误差用整数运算） */
    private String yuan(Long cent) {
        long v = cent == null ? 0L : cent;
        return (v / 100) + "." + String.format("%02d", Math.abs(v % 100));
    }
}
