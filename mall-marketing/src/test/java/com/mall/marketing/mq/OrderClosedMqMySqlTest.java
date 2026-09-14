package com.mall.marketing.mq;

import com.mall.marketing.service.CouponCommandService;
import com.mall.marketing.support.MarketingTestBase;
import com.mall.marketing.support.MqMessages;
import com.mall.marketing.support.MqTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>P5 步骤 E 的核心验收</b>：{@code order.closed} → 券解锁兜底的<b>真 MQ + 真库</b>端到端。
 *
 * <h2>为什么必须真 broker（而不是直接调 {@code CouponCommandService.unlock}）</h2>
 * 本批要证明的正是"<b>消费侧接上了</b>"：事件从**交换机 + 路由键**进来、经本服务<b>自己的队列</b>、
 * 被本服务的消费者执行幂等 {@code unlock}。直接调 service 只能证明 SQL 对，证明不了拓扑对
 * ——队列绑错一个路由键、或者绑成了跟别的服务**竞争消费**，直接调 service 的用例照样绿。
 * 因此这里用 {@link RabbitTemplate} 按单体 {@code OrderEventPublisher#publishRawAfterCommit}
 * <b>完全相同的方式</b>投递：同一个交换机 {@link MqTopology#EVENT_EXCHANGE}、同样的路由键
 * {@link MqTopology#EVENT_ROUTING_CLOSED}、同样的 JSON 正文与头
 * （{@code MqMessages.json(payload, null, orderNo, Map.of())}）。
 *
 * <h2>broker 与开关</h2>
 * 测试期默认 {@code mall.mq.enabled=false}（主 {@code application.yaml} 的
 * {@code ${MALL_MQ_ENABLED:false}}）——消费者与拓扑<b>都不装配、根本不建连</b>，
 * 因此没装 RabbitMQ 的机器上其余套件照常跑。本套件用 {@code @SpringBootTest(properties=...)}
 * 单独打开，broker 不可达时按 assumption 跳过（不把"本机没起 RabbitMQ"变成红灯）。
 *
 * <h2>断言的是"效果"，不是"谁解的"</h2>
 * 若本机同时跑着一个真的 {@code mall-marketing} 进程（开发时常见），它就是同一队列上的
 * <b>另一个竞争消费者</b>——消息可能被它消费掉。这不影响本套件：消费方是谁都执行同样的幂等
 * {@code unlock}，断言看的是<b>库里券的状态</b>。真正的拓扑不变量（本服务有自己的三个队列、
 * 不与别的服务同名）由 {@link #topology_declaresOwnQueuesInsteadOfCompeting()} 单独钉住。
 *
 * <h2>四个场景</h2>
 * <ol>
 *   <li>拓扑：本服务的 {@code mall.marketing.order-closed}(+retry+dlq) 被声明出来，且名字与
 *       单体/{@code user-center}/{@code review} 的队列都不同（扇出而不是竞争）；</li>
 *   <li>{@code order.closed} → {@code LOCKED(3)} 的券回到 {@code UNUSED(0)}，
 *       且 {@code order_no} / {@code lock_time} 都被清空；</li>
 *   <li>同一事件<b>重复投递两次</b>仍然只解一次，且<b>不会碰到别的单锁着的券</b>（不误放）；
 *       同时证明解锁 ≠ 核销（{@code use_time} 保持 null）；</li>
 *   <li>{@code couponMemberId == null}（没用券的订单）直接 ack，队列不卡住：
 *       紧随其后的一条真实事件照样被处理。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        // ⚠️ 子类声明 @SpringBootTest 会**替换**基类那份 properties，因此基类的两个令牌必须重述
        //（同 mall-review 的 OrderFinishedMqMySqlTest；基类 MarketingTestBase 里就写着这两个值）
        "mall.internal.token=test-internal-token",
        "mall.gateway.auth-token=test-gateway-token",
        // 只对本套件打开 MQ（默认 false：其它套件不连 broker）
        "mall.mq.enabled=true",
        // 失败重试压到 200ms/1 次（默认 10s/3 次对用例太慢），便于秒级断言失败链路
        "mall.mq.event-retry-delay-ms=200",
        "mall.mq.event-max-retry=1"
})
@AutoConfigureMockMvc
class OrderClosedMqMySqlTest extends MarketingTestBase {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private CouponCommandService couponCommandService;

    @Value("${spring.rabbitmq.host:127.0.0.1}")
    private String rabbitHost;

    @Value("${spring.rabbitmq.port:5672}")
    private int rabbitPort;

    @BeforeEach
    void requireRabbitMq() {
        assumeTrue(rabbitUp(), "RabbitMQ 未启动(" + rabbitHost + ":" + rabbitPort + " 不通)，跳过");
    }

    @Test
    @DisplayName("[MQ] 拓扑：本服务声明自己的 order-closed(+retry+dlq)，不与别的服务竞争消费")
    void topology_declaresOwnQueuesInsteadOfCompeting() {
        // 为什么用队列当哨兵：RabbitAdmin 的声明顺序是「交换机 → 队列 → 绑定」，
        // 共享交换机 mall.oms.event 的参数一旦与单体不一致，RabbitMQ 会 PRECONDITION_FAILED，
        // 后面的队列/绑定就不会被声明出来 —— 所以"队列存在"本身就证明交换机声明成功了。
        assertThat(amqpAdmin.getQueueInfo(MqTopology.MARKETING_QUEUE)).as("工作队列").isNotNull();
        assertThat(amqpAdmin.getQueueInfo(MqTopology.MARKETING_RETRY_QUEUE)).as("重试队列").isNotNull();
        assertThat(amqpAdmin.getQueueInfo(MqTopology.MARKETING_DLQ)).as("死信队列").isNotNull();

        // 消费者已挂在工作队列上（监听容器起来了）—— 开发机上若同时跑着真的 marketing 进程，
        // 这里会计到 2 个消费者，同样满足"≥1"这条断言。
        assertThat(amqpAdmin.getQueueInfo(MqTopology.MARKETING_QUEUE).getConsumerCount())
                .as("本服务的 order.closed 消费者应已挂在工作队列上（否则手动 ack 的那套逻辑根本没跑）")
                .isGreaterThanOrEqualTo(1);

        // 队列名就是"谁是消费者"的边界：同名 = 竞争消费 → 券会"偶尔没被兜底解锁"
        assertThat(MqTopology.MARKETING_QUEUE)
                .as("不能复用别人的队列名，否则同一条事件只会有其中一个服务拿到")
                .isNotIn("mall.oms.events", "mall.user.notification", "mall.review.order-finished",
                        "mall.pms.es-sync");
    }

    @Test
    @DisplayName("[MQ] order.closed → 锁定的券回到未使用，order_no 与 lock_time 都被清空")
    void orderClosed_unlocksLockedCoupon() {
        long couponMemberId = lockOneCoupon("E-CLOSED-1");

        assertThat(dbStatusOf(couponMemberId)).as("前置：券应处于 LOCKED(3)").isEqualTo(3);

        publishClosed("E-CLOSED-1", memberId, couponMemberId, OrderClosedMessage.REASON_CANCEL);

        assertThat(waitUntil(5_000, () -> dbStatusOf(couponMemberId) == 0))
                .as("消费 order.closed 后券应回到 UNUSED(0)（当前状态=%s）", dbStatusOf(couponMemberId))
                .isTrue();
        assertThat(dbOrderNoOf(couponMemberId)).as("解锁必须清掉 order_no（否则下次用券会被判'不属于本单'）").isNull();
        assertThat(lockTimeOf(couponMemberId)).as("解锁必须清掉 lock_time（否则会被每日对账重复捞出来）").isNull();
        assertThat(useTimeOf(couponMemberId)).as("解锁 ≠ 核销：use_time 必须保持 null").isNull();
    }

    @Test
    @DisplayName("[MQ] 重复投递只解一次，且不会误放别的单锁着的券")
    void duplicateDelivery_isIdempotentAndDoesNotTouchOtherCoupons() {
        long first = lockOneCoupon("E-CLOSED-DUP-1");
        long second = lockOneCoupon("E-CLOSED-DUP-2");   // 另一张券、另一单，必须原封不动

        OrderClosedMessage payload = new OrderClosedMessage(
                "E-CLOSED-DUP-1", memberId, first, OrderClosedMessage.REASON_TIMEOUT, System.currentTimeMillis());
        sendRaw(payload);
        sendRaw(payload);   // 重复投递（at-least-once 下必然发生）

        assertThat(waitUntil(5_000, () -> dbStatusOf(first) == 0)).as("第一张券应被解锁").isTrue();
        // 给第二条重复消息一点时间落地，再确认它没有产生任何副作用
        assertThat(waitUntil(1_500, () -> false)).as("（等待窗口：让重复消息被消费完）").isFalse();

        assertThat(dbStatusOf(first)).as("重复投递后仍是 0（不可能被解第二次）").isEqualTo(0);
        assertThat(dbStatusOf(second)).as("别人的券必须一动不动").isEqualTo(3);
        assertThat(dbOrderNoOf(second)).as("别人的券 order_no 不能被改").isEqualTo("E-CLOSED-DUP-2");
    }

    @Test
    @DisplayName("[MQ] couponMemberId==null（没用券的单）直接 ack，紧随其后的真实事件照样被处理")
    void nullCouponMember_isAcked_andNextEventStillProcessed() {
        // 1) 没用券的订单关单：载荷里 couponMemberId=null，消费者直接 ack（不抛、不卡队列）
        sendRaw(new OrderClosedMessage("E-CLOSED-NOCoupon", memberId, null,
                OrderClosedMessage.REASON_CANCEL, System.currentTimeMillis()));

        // 2) 紧接着一条真实事件：如果上一条没被 ack（或抛异常进了重试），这条就不会及时被处理
        long couponMemberId = lockOneCoupon("E-CLOSED-AFTER-NULL");
        publishClosed("E-CLOSED-AFTER-NULL", memberId, couponMemberId, OrderClosedMessage.REASON_ADMIN_CLOSE);

        assertThat(waitUntil(5_000, () -> dbStatusOf(couponMemberId) == 0))
                .as("null 事件之后队列仍应正常消费（否则说明前一条没 ack）").isTrue();
    }

    // ==================== helpers ====================

    /** 造一张券并用真实业务入口锁上它（顺带验证 lock 的 3 态与 lock_time 写入） */
    private long lockOneCoupon(String orderNo) {
        long templateId = newTemplate(1_000L);
        long couponMemberId = newCouponMember(templateId);

        assertThat(couponCommandService.lock(memberId, couponMemberId, orderNo)).as("锁券应成功").isTrue();
        assertThat(dbStatusOf(couponMemberId)).isEqualTo(3);
        assertThat(dbOrderNoOf(couponMemberId)).isEqualTo(orderNo);
        assertThat(lockTimeOf(couponMemberId)).as("lock 必须写 lock_time（对账靠它判断'锁太久'）").isNotNull();
        return couponMemberId;
    }

    /** 按单体发布方的口径投递（同一交换机 + 同一路由键 + 同样的正文/头） */
    private void publishClosed(String orderNo, Long memberId, Long couponMemberId, String reason) {
        sendRaw(new OrderClosedMessage(orderNo, memberId, couponMemberId, reason, System.currentTimeMillis()));
    }

    private void sendRaw(OrderClosedMessage payload) {
        rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, MqTopology.EVENT_ROUTING_CLOSED,
                MqMessages.json(payload, null, payload.orderNo(), Map.of()));
    }

    private java.time.LocalDateTime lockTimeOf(long couponMemberId) {
        return dateTimeOf("SELECT lock_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponMemberId);
    }

    private java.time.LocalDateTime useTimeOf(long couponMemberId) {
        return dateTimeOf("SELECT use_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponMemberId);
    }

    /** 轮询到条件成立（或超时）；MQ 是异步的，这是本项目既有的等待口径 */
    private boolean waitUntil(long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** 先探 TCP，避免 broker 未启动时用例失败（而不是跳过） */
    private boolean rabbitUp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(rabbitHost, rabbitPort), 1_500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
