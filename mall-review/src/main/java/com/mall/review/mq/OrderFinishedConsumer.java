package com.mall.review.mq;

import com.mall.review.service.ReviewPendingService;
import com.mall.review.support.MqMessages;
import com.mall.review.support.MqTopology;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import com.mall.common.support.MemberId;

/**
 * 确认收货（{@code order.finished}）事件的消费者：<b>P4-2 起"待评价读模型"的唯一写入方</b>。
 *
 * <p>监听自己的队列 {@link MqTopology#REVIEW_QUEUE}（{@code mall.review.order-finished}），
 * 该队列与单体的 {@code mall.oms.events}、user-center 的 {@code mall.user.notification}
 * 一起绑在同一条事件交换机 {@code mall.oms.event} 上，业务键 {@code order.finished} 照抄。
 * 于是同一条事件<b>三个服务各收到一份</b>：单体重算订单日统计、user-center 写站内消息、
 * 本服务投影待评价行（一个副作用一个属主）。
 *
 * <h2>为什么是独立队列而不是复用别人的队列名</h2>
 * 同名队列 = 竞争消费：一条消息只会被其中一个服务拿到，"待评价列表"或"站内消息"会随机各丢一半。
 * 队列名就是消费者边界，见 {@link MqTopology} 的类注释。
 *
 * <h2>幂等：靠主键，不靠"只消费一次"</h2>
 * MQ 的投递语义是 at-least-once，重复投递是常态（重试、重投、broker 重启）。
 * 这里对重复投递<b>不做任何应用层判断</b>——{@code review_pending_item} 的主键
 * {@code order_item_id} + {@code ON DUPLICATE KEY UPDATE}（无有意义变化）才是唯一防线
 * （先查后插在并发重复投递下会双写）。因此重复投递时日志会显示"新增 0 已存在 N"，这是正常的。
 *
 * <h2>做什么、不做什么</h2>
 * 本消费者<b>只写读模型</b>：把每条明细的展示快照（标题/图片/数量）+ 订单号/会员/收货时间落进
 * {@code review_pending_item}，{@code commented = 0} 表示"这条明细还没被评价过"。
 * 提交评价的校验与闸门（把 {@code commented} 从 0 抢成 1）属于评价写业务，由 P4-3 搬过来，
 * 本批刻意不碰——本批的验收就是"事件进来，读模型长出来，且重复投递不重复长"。
 *
 * <h2>失败处理</h2>
 * 手动 ack（{@code spring.rabbitmq.listener.simple.acknowledge-mode=manual}）：
 * 成功 ack；失败 → 重试队列（消息自带 TTL，到期回本队列）；重试超限或正文解析失败 → 死信队列。
 * 三种情况都 ack 原始消息，避免毒消息无限重入（重投入口见 {@link ReviewEventRetryPublisher}）。
 *
 * <p>一个刻意的例外：事件的 {@code memberId} 为 null 时<b>不重试也不进死信</b>，只告警并 ack。
 * 读模型的 {@code member_id} 是 NOT NULL，且评价归属校验离不开它——重投一万次结果都一样，
 * 把它灌进死信队列只会污染"值得人工处理"这个信号（与 user-center 的「没有收件人」分支同一取舍）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.mq.enabled", havingValue = "true", matchIfMissing = true)
public class OrderFinishedConsumer {

    private final ReviewPendingService reviewPendingService;
    private final ReviewEventRetryPublisher reviewEventRetryPublisher;

    @Value("${mall.mq.event-max-retry:3}")
    private int maxRetry;

    @RabbitListener(queues = MqTopology.REVIEW_QUEUE)
    public void onOrderFinished(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int retry = MqMessages.retryCount(message);

        OrderFinishedMessage event;
        try {
            event = MqMessages.payload(message, OrderFinishedMessage.class);
        } catch (Exception e) {
            log.error("确认收货事件无法解析，进入死信队列: {}", e.getMessage());
            reviewEventRetryPublisher.publishRawToDlq(message, retry, "消息解析失败: " + e.getMessage());
            channel.basicAck(deliveryTag, false);
            return;
        }

        if (event == null || event.memberId() == null) {
            // 没有归属就写不出可用的读模型行；重投不会改变结果，因此不进重试/死信（见类注释）
            log.warn("确认收货事件缺少会员信息，无法投影待评价读模型: orderNo={}",
                    event == null ? null : event.orderNo());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            int inserted = reviewPendingService.projectOrderFinished(event);
            log.info("确认收货事件处理完成: orderNo={} memberId={} 读模型新增={}",
                    event.orderNo(), event.memberId(), inserted);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("待评价读模型写入失败: orderNo={} 原因={}", event.orderNo(), e.getMessage());
            if (retry + 1 <= maxRetry) {
                reviewEventRetryPublisher.publishRetry(event, retry + 1);
            } else {
                reviewEventRetryPublisher.publishToDlq(event, retry + 1,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            channel.basicAck(deliveryTag, false);
        }
    }
}
