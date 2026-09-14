package com.mall.usercenter.mq;

import com.mall.usercenter.support.MqMessages;
import com.mall.usercenter.support.MqTopology;
import com.mall.usercenter.support.UserCenterTestBase;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>P3-5 的核心验收</b>：本服务作为 {@code ums_notification} 唯一写入方，真 MQ 端到端跑通。
 *
 * <p>与单体套件的约定一致：测试期默认 {@code mall.mq.enabled=false}（见
 * {@code src/test/resources/application.properties}），本套件用
 * {@code @SpringBootTest(properties=...)} 单独打开——于是它是<b>唯一</b>会连 broker 的套件，
 * 其余套件在没装 RabbitMQ 的机器上照常跑。
 *
 * <p>为什么必须真 MQ（而不是直接调 {@code NotificationService.push}）：本批次要证明的正是
 * "消费侧接上了"——事件从<b>交换机 + 路由键</b>进来、经本服务自己的队列、被本服务的消费者写出库。
 * 直接调 push 只能证明写入 SQL 对，证明不了拓扑对（队列绑错一个键，push 测试照样绿）。
 * 因此这里用 {@link RabbitTemplate} 按单体 {@code OrderEventPublisher} <b>完全相同的方式</b>投递：
 * 同一个交换机 {@link MqTopology#EVENT_EXCHANGE}、同样的路由键、同样的 JSON 正文与头。
 *
 * <p>三条不变量（对应 P3-5 验收）：
 * <ol>
 *   <li>支付/发货/退款三条事件各写一条消息，且<b>类型/标题/正文/业务单号逐字与改造前一致</b>
 *       （文案是从单体搬过来的，前端与测试都在断言它）；</li>
 *   <li><b>重复投递只落一条</b>——at-least-once 下靠唯一键 {@code (member_id,type,biz_no)} 幂等；</li>
 *   <li>落库的库是 <b>{@code mall_user}</b>（不是单体的 {@code mall}）：本服务写自己的库。</li>
 * </ol>
 *
 * <p>broker 不可达时按 assumption 跳过（并打印原因），不把"本机没起 RabbitMQ"变成红灯。
 */
@SpringBootTest(properties = {
        // ⚠️ 子类声明 @SpringBootTest 会**替换**基类的 properties，因此基类那两个令牌必须重述
        "mall.internal.token=test-internal-token",
        "mall.gateway.auth-token=test-gateway-token",
        "mall.mq.enabled=true",
        "mall.mq.event-retry-delay-ms=200",
        "mall.mq.event-max-retry=1"
})
@AutoConfigureMockMvc
class NotificationEventMqMySqlTest extends UserCenterTestBase {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Value("${spring.rabbitmq.host:127.0.0.1}")
    private String rabbitHost;

    @Value("${spring.rabbitmq.port:5672}")
    private int rabbitPort;

    private long memberId;

    @BeforeEach
    void setUp() {
        assumeTrue(rabbitUp(), "RabbitMQ 未启动(" + rabbitHost + ":" + rabbitPort + " 不通)，跳过真 MQ 用例");
        // 第一次 broker 操作会触发 RabbitAdmin 声明全部拓扑（它挂在"连接建立"事件上），
        // 因此先等自己的三个队列真的出现——这同时是"拓扑声明成功"的断言（参数不一致会 PRECONDITION_FAILED），
        // 也是后面"队列深度=0"断言成立的前提；再排空，避免把上一轮的残留消息当成这一轮的
        for (String queue : new String[]{MqTopology.NOTIFICATION_QUEUE, MqTopology.NOTIFICATION_RETRY_QUEUE,
                MqTopology.NOTIFICATION_DLQ}) {
            assertTrue(waitUntil(10_000, () -> queueDepth(queue) >= 0),
                    "拓扑未声明：队列 " + queue + " 不存在（RabbitMQ 参数不一致会被 PRECONDITION_FAILED 拒绝）");
        }
        purgeNotificationQueues();
        memberId = insertMember("uc_mq_");
    }

    @AfterEach
    void cleanUp() {
        if (memberId > 0) {
            deleteMember(memberId);   // 连同该会员的 ums_notification 一起清掉
        }
        purgeNotificationQueues();
    }

    @Test
    @DisplayName("[MQ] order.paid → mall_user.ums_notification 落一条；重复投递仍只有一条")
    void paidEvent_writesExactlyOneNotification() {
        String orderNo = "TEST-P3-5-PAID-" + System.nanoTime();

        publish(MqTopology.EVENT_ROUTING_PAID, OrderEventMessage.paid(orderNo, memberId, 12345L));

        assertTrue(waitUntil(15_000, () -> notificationRows(orderNo) == 1),
                "支付事件应在 mall_user.ums_notification 写一条（消费者 = 本服务自己的队列）");

        Map<String, Object> row = notificationRow(orderNo);
        assertEquals("mall_user", schemaName(), "站内消息必须落在本服务自己的库");
        assertEquals(OrderEventMessage.TYPE_ORDER_PAID, row.get("type"));
        assertEquals(orderNo, row.get("biz_no"));
        assertEquals("支付成功", row.get("title"));
        // 文案逐字与改造前（单体 oms.mq.OrderEventConsumer）一致：含订单号与"分 → 元"后的实付金额
        assertEquals("订单 " + orderNo + " 已支付成功，实付 123.45 元，我们会尽快为你发货。", row.get("content"));
        assertEquals(0, ((Number) row.get("is_read")).intValue());

        // —— 重复投递（at-least-once 的常态）：同一条事件再投两次 ——
        publish(MqTopology.EVENT_ROUTING_PAID, OrderEventMessage.paid(orderNo, memberId, 12345L));
        publish(MqTopology.EVENT_ROUTING_PAID, OrderEventMessage.paid(orderNo, memberId, 12345L));
        assertTrue(waitUntil(15_000, () -> queueDepth(MqTopology.NOTIFICATION_QUEUE) == 0),
                "重复消息应被消费掉（不是堆在队列里）");
        // 队列空 ≠ 已落库（消息可能已投递给消费者但还在处理中），因此留两个观察窗口：
        // "只有一条"必须是稳定状态，而不是"第二条还没写进去"
        sleep(1000);
        assertEquals(1L, notificationRows(orderNo),
                "重复投递只应有一条消息（幂等靠唯一键 (member_id,type,biz_no) + ON DUPLICATE KEY UPDATE id=id）");
        sleep(1000);
        assertEquals(1L, notificationRows(orderNo), "第二个观察窗口内也不应出现第二条");
    }

    @Test
    @DisplayName("[MQ] order.shipped / refund.settled → 文案逐字与改造前一致")
    void shippedAndRefundEvents_writeNotificationsWithSameText() {
        String shippedOrderNo = "TEST-P3-5-SHIP-" + System.nanoTime();
        String refundOrderNo = "TEST-P3-5-REF-" + System.nanoTime();

        publish(MqTopology.EVENT_ROUTING_SHIPPED,
                OrderEventMessage.shipped(shippedOrderNo, memberId, "顺丰速运 SF-TEST-1"));
        publish(MqTopology.EVENT_ROUTING_REFUND,
                OrderEventMessage.refundSettled(refundOrderNo, memberId, 9900L));

        assertTrue(waitUntil(15_000, () -> notificationRows(shippedOrderNo) == 1
                        && notificationRows(refundOrderNo) == 1),
                "发货与退款到账事件应各写一条站内消息");

        Map<String, Object> shipped = notificationRow(shippedOrderNo);
        assertEquals(OrderEventMessage.TYPE_ORDER_SHIPPED, shipped.get("type"));
        assertEquals("商品已发货", shipped.get("title"));
        assertEquals("订单 " + shippedOrderNo + " 已发货，物流：顺丰速运 SF-TEST-1。请留意收货。",
                shipped.get("content"));

        Map<String, Object> refund = notificationRow(refundOrderNo);
        assertEquals(OrderEventMessage.TYPE_REFUND_SETTLED, refund.get("type"));
        assertEquals("退款已到账", refund.get("title"));
        assertEquals("订单 " + refundOrderNo + " 的退款 99.00 元已处理完成，请查收。", refund.get("content"));
    }

    @Test
    @DisplayName("[MQ] 无会员信息的事件(orderNo 级)不产生消息，也不会毒住队列")
    void eventWithoutMember_producesNothing() {
        String orderNo = "TEST-P3-5-NOMEMBER-" + System.nanoTime();

        // memberId = null：事件本身合法（统计侧仍要重算），但通知没有收件人 → 不写、直接 ack
        publish(MqTopology.EVENT_ROUTING_PAID,
                new OrderEventMessage(OrderEventMessage.TYPE_ORDER_PAID, orderNo, null, 100L, null,
                        System.currentTimeMillis()));

        assertTrue(waitUntil(15_000, () -> queueDepth(MqTopology.NOTIFICATION_QUEUE) == 0),
                "没有收件人的事件应被 ack 掉（进死信或卡住都说明消费者行为变了）");
        sleep(300);
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ums_notification WHERE biz_no = ?", Long.class, orderNo));
        assertEquals(0L, queueDepth(MqTopology.NOTIFICATION_DLQ), "这类事件不该进死信队列");
    }

    // ==================== helpers ====================

    /** 与单体 {@code OrderEventPublisher.send} 完全相同的投递方式（同交换机、同路由键、同正文与头） */
    private void publish(String routingKey, OrderEventMessage event) {
        rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, routingKey,
                MqMessages.json(event, null, event.orderNo(), null));
    }

    private long notificationRows(String bizNo) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ums_notification WHERE member_id = ? AND biz_no = ?",
                Long.class, memberId, bizNo);
        return n == null ? 0L : n;
    }

    private Map<String, Object> notificationRow(String bizNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT type, title, content, biz_no, is_read FROM ums_notification WHERE member_id = ? AND biz_no = ?",
                memberId, bizNo);
        assertEquals(1, rows.size(), "期望恰好一条站内消息: bizNo=" + bizNo);
        Map<String, Object> row = rows.get(0);
        assertNotNull(row, "站内消息不存在: bizNo=" + bizNo);
        return row;
    }

    /** 队列深度；队列不存在（还没声明）返回 -1 */
    private long queueDepth(String queue) {
        try {
            var info = amqpAdmin.getQueueInfo(queue);
            return info == null ? -1 : info.getMessageCount();
        } catch (Exception e) {
            return -1;
        }
    }

    private void purgeNotificationQueues() {
        for (String q : new String[]{MqTopology.NOTIFICATION_QUEUE, MqTopology.NOTIFICATION_RETRY_QUEUE,
                MqTopology.NOTIFICATION_DLQ}) {
            try {
                amqpAdmin.purgeQueue(q);
            } catch (Exception ignored) {
                // 队列还没声明时忽略（拓扑由 RabbitAdmin 在连接建立时声明）
            }
        }
    }

    private boolean rabbitUp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(rabbitHost, rabbitPort), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean waitUntil(long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(150);
        }
        return condition.getAsBoolean();
    }
}
