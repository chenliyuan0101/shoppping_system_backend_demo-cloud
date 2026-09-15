package com.mall.trade.oms;

import com.jayway.jsonpath.JsonPath;
import com.mall.trade.common.constant.OrderStatus;
import com.mall.trade.oms.mq.OrderTimeoutPublisher;
import com.mall.trade.oms.service.OrderService;
import com.mall.trade.support.MySqlTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 订单超时关单的 RabbitMQ 链路测试（延迟消息 TTL + 死信 → 消费者关单）。
 *
 * <p>测试期全局关闭 MQ（{@code mall.mq.enabled=false}，见 src/test/resources/application.properties），
 * 本套件用 {@code @SpringBootTest(properties=...)} 单独开启；并把**兜底扫描周期放到 1 小时**，
 * 这样"订单被关掉"只可能来自 MQ 消费者，断言才有意义。
 *
 * <p>为什么不真等 30 分钟：延迟队列的 TTL 取自"订单剩余支付时间"。用例直接
 * （1）改库把 {@code pay_expire_time} 拨到过去，（2）用 {@code publishImmediately} 投到消费队列，
 * 于是可以秒级验证"消费者 → 关单 → 回补库存"整条链路。
 *
 * <p>RabbitMQ 未启动时整类跳过（{@code assumeTrue}），与本项目 Redis/MinIO/ES 套件的约定一致。
 */
@SpringBootTest(properties = {
        "mall.mq.enabled=true",
        "mall.order.timeout-scan-interval-ms=3600000"
})
@AutoConfigureMockMvc
class OrderTimeoutMqMySqlTest extends MySqlTestBase {

    /** 种子 SKU（被多套件引用，用例结束由 cleanUpBaselines 恢复库存） */
    private static final long SKU_ID = 2001;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderTimeoutPublisher orderTimeoutPublisher;

    @BeforeEach
    void requireRabbitMq() {
        assumeTrue(rabbitUp(), "RabbitMQ 未启动(127.0.0.1:5672 不通)，跳过");
    }

    @AfterEach
    void cleanUp() {
        cleanUpBaselines();
    }

    @Test
    @DisplayName("[MQ] /api/admin/mq/ping 报出三个队列，且关单消费者已挂在队列上")
    void pingReportsQueuesAndConsumer() throws Exception {

        mockMvc.perform(get("/api/admin/mq/ping").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.delayQueue.name").value("mall.oms.order-timeout.delay"))
                .andExpect(jsonPath("$.data.delayQueue.exists").value(true))
                .andExpect(jsonPath("$.data.workQueue.exists").value(true))
                .andExpect(jsonPath("$.data.deadLetterQueue.exists").value(true));

        // 监听容器是异步启动的，给一点时间等它挂上队列（consumers >= 1）
        boolean attached = waitUntil(15_000, () -> queueStat("workQueue", "consumers") >= 1);
        assertThat(attached).as("关单消费者应已挂到 %s", "mall.oms.order-timeout").isTrue();
    }

    @Test
    @DisplayName("[MQ] 下单后延迟队列收到一条消息（事务提交后投递）")
    void createOrderPublishesDelayedMessage() throws Exception {
        String token = registerAs("mqpub");
        long addressId = createAddress(token);

        long before = queueStat("delayQueue", "messages");
        rememberStock(SKU_ID);
        buyNow(token, addressId, SKU_ID, 1);

        boolean published = waitUntil(10_000, () -> queueStat("delayQueue", "messages") > before);
        assertThat(published).as("下单后延迟队列消息数应从 %s 增加", before).isTrue();
    }

    @Test
    @DisplayName("[MQ] 消费者处理到期消息：关单 + 回补库存，且重复触发不会二次回补")
    void consumerClosesExpiredOrder() throws Exception {
        String token = registerAs("mqcons");
        long addressId = createAddress(token);
        rememberStock(SKU_ID);
        int baseline = stock(SKU_ID);

        String orderNo = buyNow(token, addressId, SKU_ID, 1);
        assertStock(SKU_ID, baseline - 1);

        // 把支付截止时间拨到过去（模拟到期），再投一条消息到消费队列（跳过 30 分钟延迟）
        jdbcTemplate.update("UPDATE oms_order SET pay_expire_time = NOW() - INTERVAL 1 MINUTE WHERE order_no = ?", orderNo);
        orderTimeoutPublisher.publishImmediately(orderNo);

        // ⚠️ 等待条件必须对准**真正的不变量**（P6-4 D1 之后才会红的那种写法）：
        //    D1 把库存回补移进 `StockReleaseAfterCommit`（事务**提交后**执行）⇒
        //    "订单状态对别的连接可见"与"库存已回补"**不再同刻发生**（先 commit 状态、再跑 afterCommit），
        //    而本断言跑在另一个连接上：只等"状态变了"就会撞进那个毫秒级窗口（实测 expected 9 but was 8）。
        //    所以等的是最终条件"**状态已取消 且 库存已回到基线**"。
        //    这不是放宽断言 —— 真失败（回补压根没发生）15s 后照样红；
        //    先例是我们自己的 `drainIndexSyncQueueUntil(converged)`：以可观测的最终条件为准，而不是固定 sleep。
        // ⚠️ 本用例**依赖 `mall.oms.order-timeout` 队列独占**（P6-4 主 agent 要求写明的残留依赖）：
        //    窗口前本机还跑着 P6-4 之前的**旧 jar**，它也在消费同一队列，并且把回补写进 `mall.pms_sku`
        //    （旧代码走本地实现），而本用例与测试替身读写的是 `mall_product.pms_sku` ⇒ 必然超时红。
        //    窗口（W3）之后单体是新 jar、回补走远程 product（同一个库、同一套队列语义）⇒ 该依赖自然消失。
        boolean closed = waitUntil(15_000,
                () -> orderStatus(orderNo) == OrderStatus.CANCELED && stock(SKU_ID) == baseline);
        assertThat(closed).as("订单 %s 应被 MQ 消费者关掉(状态 4)且库存已回补", orderNo).isTrue();
        assertStock(SKU_ID, baseline);

        // 幂等：再触发一次不应重复回补库存
        assertThat(orderService.closeIfExpired(orderNo)).isFalse();
        assertStock(SKU_ID, baseline);
    }

    @Test
    @DisplayName("[MQ] 未到期的订单即使收到消息也不会被关单")
    void closeIfExpiredSkipsNotYetExpired() throws Exception {
        String token = registerAs("mqskip");
        long addressId = createAddress(token);
        rememberStock(SKU_ID);
        int baseline = stock(SKU_ID);

        String orderNo = buyNow(token, addressId, SKU_ID, 1);

        assertThat(orderService.closeIfExpired(orderNo)).as("未到期不应关单").isFalse();
        assertThat(orderStatus(orderNo)).isEqualTo(OrderStatus.WAIT_PAY);
        assertStock(SKU_ID, baseline - 1);
    }

    // ==================== helpers ====================

    private boolean rabbitUp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5672), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int orderStatus(String orderNo) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT order_status FROM oms_order WHERE order_no = ?", Integer.class, orderNo);
        return v == null ? -1 : v;
    }

    /** 读一次 /api/admin/mq/ping 里某个队列的某个字段(messages / consumers) */
    private long queueStat(String queueKey, String field) {
        try {
            String body = mockMvc.perform(get("/api/admin/mq/ping").headers(adminHeaders()))
                    .andReturn().getResponse().getContentAsString();
            Number v = JsonPath.read(body, "$.data." + queueKey + "." + field);
            return v == null ? 0 : v.longValue();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 轮询等待条件成立(异步链路断言用)，超时返回 false */
    private boolean waitUntil(long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
