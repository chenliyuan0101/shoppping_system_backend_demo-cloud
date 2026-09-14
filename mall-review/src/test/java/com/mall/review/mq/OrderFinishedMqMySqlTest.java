package com.mall.review.mq;

import com.mall.review.support.MallTime;
import com.mall.review.support.MqMessages;
import com.mall.review.support.MqTopology;
import com.mall.review.support.ReviewTestBase;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>P4-2 的核心验收</b>：{@code order.finished} → 待评价读模型的真 MQ 端到端。
 *
 * <p>与其它服务的约定一致：测试期默认 {@code mall.mq.enabled=false}（见
 * {@code src/test/resources/application.properties}），本套件用
 * {@code @SpringBootTest(properties=...)} 单独打开——于是它是<b>唯一</b>会连 broker 的套件，
 * 其余套件在没装 RabbitMQ 的机器上照常跑。
 *
 * <p>为什么必须真 MQ（而不是直接调 {@code ReviewPendingService}）：本批要证明的正是
 * "消费侧接上了"——事件从<b>交换机 + 路由键</b>进来、经本服务自己的队列、被本服务的消费者写出库。
 * 直接调 service 只能证明写入 SQL 对，证明不了拓扑对（队列绑错一个键、或者绑成了竞争消费，
 * 写入用例照样绿）。因此这里用 {@link RabbitTemplate} 按单体 {@code OrderEventPublisher}
 * <b>完全相同的方式</b>投递：同一个交换机 {@link MqTopology#EVENT_EXCHANGE}、同样的路由键、
 * 同样的 JSON 正文与头（{@code MqMessages.json(payload, null, orderNo, Map.of())}）。
 *
 * <p>四条不变量（对应 P4-2 验收）：
 * <ol>
 *   <li><b>拓扑声明成功</b>：本服务自己的三个队列出现——共享交换机 {@code mall.oms.event} 的声明参数
 *       一旦与单体不一致，RabbitMQ 会 {@code PRECONDITION_FAILED} 让监听容器起不来，
 *       这条断言就是那个事故的哨兵；</li>
 *   <li><b>扇出而不是竞争</b>：单体 {@code mall.oms.events} 与 user-center {@code mall.user.notification}
 *       两个队列依然存在、名字没变（本服务用的是自己的第三个队列名）；</li>
 *   <li><b>事件真的落进读模型</b>：3 条明细 → 3 行，字段与事件正文逐字对应（含业务时区换算的
 *       {@code finished_time}），且 {@code commented=0}（等待评价）；</li>
 *   <li><b>重复投递不产生重复行、也不改变已有行</b>——at-least-once 下靠主键 + ON DUPLICATE KEY UPDATE。</li>
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
class OrderFinishedMqMySqlTest extends ReviewTestBase {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Value("${spring.rabbitmq.host:127.0.0.1}")
    private String rabbitHost;

    @Value("${spring.rabbitmq.port:5672}")
    private int rabbitPort;

    /** 本用例造的明细 ID 前缀：刻意远离真实数据（回填出来的那批在 900 万量级） */
    private static final long BASE = 7_600_000_000_000L;

    private long itemId1;
    private long itemId2;
    private long itemId3;
    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        assumeTrue(rabbitUp(), "RabbitMQ 未启动(" + rabbitHost + ":" + rabbitPort + " 不通)，跳过真 MQ 用例");
        // 第一次 broker 操作会触发 RabbitAdmin 声明全部拓扑（它挂在"连接建立"事件上），
        // 因此先等自己的三个队列真的出现——这同时是"拓扑声明成功"的断言（参数不一致会 PRECONDITION_FAILED），
        // 也是后面"队列深度=0"断言成立的前提；再排空，避免把上一轮的残留消息当成这一轮的
        for (String queue : new String[]{MqTopology.REVIEW_QUEUE, MqTopology.REVIEW_RETRY_QUEUE,
                MqTopology.REVIEW_DLQ}) {
            assertTrue(waitUntil(10_000, () -> queueDepth(queue) >= 0),
                    "拓扑未声明：队列 " + queue + " 不存在（共享交换机声明参数与单体不一致会被 PRECONDITION_FAILED 拒绝）");
        }
        purgeReviewQueues();

        long seq = System.nanoTime() % 1_000_000L;
        itemId1 = BASE + seq * 10;
        itemId2 = itemId1 + 1;
        itemId3 = itemId1 + 2;
        created.clear();
        created.add(itemId1);
        created.add(itemId2);
        created.add(itemId3);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : created) {
            jdbcTemplate.update("DELETE FROM mall_review.review_pending_item WHERE order_item_id = ?", id);
        }
        purgeReviewQueues();
    }

    @Test
    @DisplayName("[MQ] order.finished → review_pending_item 落 3 行且字段逐字对应；重复投递仍只有 3 行、内容不变")
    void orderFinished_projectsPendingRows_andIsIdempotent() {
        String orderNo = "TEST-P4-2-" + System.nanoTime();
        long memberId = 900616L;
        // 固定一个"业务时区"的收货时间：断言读模型里的 datetime 就是它，证明毫秒戳的换算口径正确
        LocalDateTime finishedLocal = LocalDateTime.of(2026, 9, 1, 10, 30, 0);
        long finishedMillis = finishedLocal.atZone(MallTime.ZONE).toInstant().toEpochMilli();

        OrderFinishedMessage event = new OrderFinishedMessage(orderNo, memberId, finishedMillis, List.of(
                new OrderFinishedMessage.Item(itemId1, 20454L, 30501L, "测试商品A", "http://img/a.png", 2),
                new OrderFinishedMessage.Item(itemId2, 20455L, 30502L, "测试商品B", "http://img/b.png", 1),
                new OrderFinishedMessage.Item(itemId3, 20456L, null, "测试商品C", null, 3)));

        publish(event);

        assertTrue(waitUntil(15_000, () -> pendingRows(orderNo) == 3),
                "确认收货事件应投影出 3 行待评价（消费者 = 本服务自己的队列 " + MqTopology.REVIEW_QUEUE + "）");
        assertEquals("mall_review", schemaName(), "读模型必须落在本服务自己的库");

        Map<String, Object> row1 = pendingRow(itemId1);
        assertEquals(orderNo, row1.get("order_no"));
        assertEquals(memberId, ((Number) row1.get("member_id")).longValue());
        assertEquals(20454L, ((Number) row1.get("spu_id")).longValue());
        assertEquals("测试商品A", row1.get("spu_title"));
        assertEquals("http://img/a.png", row1.get("sku_image"));
        assertEquals(2, ((Number) row1.get("quantity")).intValue());
        assertEquals(finishedLocal, row1.get("finished_time"),
                "finished_time 必须是事件里的毫秒戳按业务时区(MallTime.ZONE)换算出来的那个时刻");
        assertEquals(0, ((Number) row1.get("commented")).intValue(),
                "事件投影出来的行是**待**评价：commented 必须是 0，否则闸门一上线就把它判成已评价");
        assertTrue(row1.get("comment_id") == null, "还没评价过，comment_id 必须为 NULL");

        // 第 3 条明细刻意不带 skuId/skuImage：可空列必须能落 NULL（不能因为 null 让整条事件反复重试）
        Map<String, Object> row3 = pendingRow(itemId3);
        assertEquals("测试商品C", row3.get("spu_title"));
        assertTrue(row3.get("sku_id") == null);
        assertTrue(row3.get("sku_image") == null);
        assertEquals(3, ((Number) row3.get("quantity")).intValue());

        Map<String, Object> snapshotBefore = pendingRow(itemId1);

        // —— 重复投递（at-least-once 的常态）：同一条事件再投两次 ——
        publish(event);
        publish(event);
        assertTrue(waitUntil(15_000, () -> queueDepth(MqTopology.REVIEW_QUEUE) == 0),
                "重复消息应被消费掉（不是堆在队列里）");
        // 队列空 ≠ 已落库（消息可能已投递给消费者但还在处理中），因此留两个观察窗口：
        // "只有 3 行"必须是稳定状态，而不是"第二轮还没写进去"
        sleep(1000);
        assertEquals(3L, pendingRows(orderNo),
                "重复投递只应产生 3 行（幂等靠主键 order_item_id + ON DUPLICATE KEY UPDATE 无有意义变化）");
        assertEquals(snapshotBefore, pendingRow(itemId1),
                "重复投递**不得改变**已有行（连字段值都不能漂：这是'无有意义变化'的可执行定义）");
        sleep(1000);
        assertEquals(3L, pendingRows(orderNo), "第二个观察窗口内也不应出现第 4 行");

        assertEquals(0L, queueDepth(MqTopology.REVIEW_DLQ),
                "正常事件不该进死信队列（进了说明消费者把它当失败了）");
    }

    @Test
    @DisplayName("[MQ] 扇出：本服务用自己的队列，单体与 user-center 的队列依然存在、名字未被占用")
    void fanOut_siblingQueuesSurvive() {
        // 这条断言守的是"同名队列 = 竞争消费"这个事故：
        // 如果本服务图省事复用了 mall.oms.events / mall.user.notification，那两个队列会被声明成
        // 同一批绑定，一条事件只会被其中一个服务拿到（现象随机、难以复现）。
        // 因此这里断言的正是"三个队列是三个不同的队列名，且另外两个还在"。
        assertNotNull(amqpAdmin.getQueueInfo(MqTopology.REVIEW_QUEUE), "本服务的工作队列必须存在");
        assertNotNull(amqpAdmin.getQueueInfo("mall.oms.events"),
                "单体的工作队列必须还在（扇出：它在同一个交换机上有自己的绑定）");
        assertNotNull(amqpAdmin.getQueueInfo("mall.user.notification"),
                "user-center 的通知队列必须还在（扇出：P3-5 的绑定不受本批影响）");

        assertTrue(!MqTopology.REVIEW_QUEUE.equals("mall.oms.events")
                        && !MqTopology.REVIEW_QUEUE.equals("mall.user.notification"),
                "本服务必须用自己的队列名（复用 = 竞争消费）");
    }

    // ==================== helpers ====================

    /** 与单体 {@code OrderEventPublisher#publishRawAfterCommit} 完全相同的投递方式（同交换机、同路由键、同正文与头） */
    private void publish(OrderFinishedMessage event) {
        rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, MqTopology.EVENT_ROUTING_FINISHED,
                MqMessages.json(event, null, event.orderNo(), Map.of()));
    }

    private long pendingRows(String orderNo) {
        return countOf("SELECT COUNT(*) FROM mall_review.review_pending_item WHERE order_no = ?", orderNo);
    }

    private Map<String, Object> pendingRow(long orderItemId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_item_id, order_no, member_id, spu_id, sku_id, spu_title, sku_image, quantity,"
                        + " finished_time, commented, comment_id FROM mall_review.review_pending_item"
                        + " WHERE order_item_id = ?", orderItemId);
        assertEquals(1, rows.size(), "期望恰好一行待评价: orderItemId=" + orderItemId);
        return rows.get(0);
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

    private void purgeReviewQueues() {
        for (String q : new String[]{MqTopology.REVIEW_QUEUE, MqTopology.REVIEW_RETRY_QUEUE, MqTopology.REVIEW_DLQ}) {
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
