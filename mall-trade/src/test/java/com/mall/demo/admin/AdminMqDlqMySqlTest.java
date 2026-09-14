package com.mall.demo.admin;

import com.jayway.jsonpath.JsonPath;
import com.mall.demo.common.MqMessages;
import com.mall.demo.common.MqTopology;
import com.mall.demo.oms.mq.OrderTimeoutMessage;
import com.mall.demo.support.MySqlTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 死信队列重投接口测试（{@code POST /api/admin/mq/dlq/requeue}）。
 *
 * <p>测法：把**内容合法**的消息直接投到死信队列（模拟"下游修好后待重投"的场景），
 * 再调重投接口，验证消息被放回工作队列并被消费者正常处理（而不是又弹回死信）。
 * 这样断言是确定性的：不依赖"故意造失败 → 等重试 → 进死信"的时序。
 */
@SpringBootTest(properties = {
        "mall.mq.enabled=true",
        "mall.mq.sync-retry-delay-ms=200",
        "mall.mq.sync-max-retry=1"
})
@AutoConfigureMockMvc
class AdminMqDlqMySqlTest extends MySqlTestBase {

    private static final long SKU_ID = 2001L;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void requireRabbitMq() {
        assumeTrue(rabbitUp(), "RabbitMQ 未启动(127.0.0.1:5672 不通)，跳过");
        purgeDlqs();
    }

    @AfterEach
    void cleanUp() {
        cleanUpBaselines();
        purgeDlqs();
    }

    @Test
    @DisplayName("[死信] 索引同步死信重投 → 消息回到工作队列并被消费，死信清空")
    void requeueProductSyncDlq() throws Exception {

        // 造一条"内容合法但被放进死信"的索引同步消息（spuIds=[1001]）
        var payload = new com.mall.demo.pms.mq.ProductSyncMessage(java.util.List.of(1001L), null, System.currentTimeMillis());
        rabbitTemplate.send(MqTopology.SYNC_EXCHANGE, MqTopology.SYNC_DLQ_ROUTING_KEY,
                MqMessages.json(payload, null, null, Map.of(MqMessages.HEADER_FAIL_REASON, "测试造的死信")));

        assertThat(waitUntil(5_000, () -> queueMessages("productSyncDlq") == 1))
                .as("死信队列应有 1 条消息").isTrue();

        String body = mockMvc.perform(post("/api/admin/mq/dlq/requeue")
                        .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queue\":\"" + MqTopology.SYNC_DLQ + "\",\"limit\":10}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requeued").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(body, "$.data.remaining")).longValue()).isZero();

        // 消费者处理完后，工作队列与死信队列都应为空（说明真的被消费掉，而不是又弹回死信）
        assertThat(waitUntil(10_000, () -> queueMessages("productSyncQueue") == 0
                && queueMessages("productSyncDlq") == 0
                && queueMessages("productSyncRetryQueue") == 0))
                .as("重投后应被正常消费，三个队列都为空").isTrue();
    }

    @Test
    @DisplayName("[死信] 超时关单死信重投 → 订单被关掉(状态 4)")
    void requeueOrderTimeoutDlq() throws Exception {
        String token = registerAs("dlqord_");
        long addressId = createAddress(token);
        rememberStock(SKU_ID);
        String orderNo = buyNow(token, addressId, SKU_ID, 1);

        // 拨到过去，并把"到期关单"消息直接投到死信队列
        jdbcTemplate.update("UPDATE oms_order SET pay_expire_time = NOW() - INTERVAL 1 MINUTE WHERE order_no = ?", orderNo);
        OrderTimeoutMessage payload = new OrderTimeoutMessage(orderNo, System.currentTimeMillis(), 0);
        rabbitTemplate.send(MqTopology.WORK_EXCHANGE, MqTopology.DLQ_ROUTING_KEY,
                MqMessages.json(payload, null, orderNo, Map.of(MqMessages.HEADER_FAIL_REASON, "测试造的死信")));

        assertThat(waitUntil(5_000, () -> queueMessages("deadLetterQueue") == 1))
                .as("关单死信队列应有 1 条消息").isTrue();

        mockMvc.perform(post("/api/admin/mq/dlq/requeue")
                        .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queue\":\"" + MqTopology.DLQ + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requeued").value(1));

        boolean closed = waitUntil(10_000, () -> {
            Integer status = jdbcTemplate.queryForObject(
                    "SELECT order_status FROM oms_order WHERE order_no = ?", Integer.class, orderNo);
            return status != null && status == 4;
        });
        assertThat(closed).as("重投后订单应被 MQ 消费者关掉(状态 4)").isTrue();
    }

    @Test
    @DisplayName("[死信] 不支持的死信队列名 → 400，不做任何重投")
    void rejectsUnknownQueue() throws Exception {
        mockMvc.perform(post("/api/admin/mq/dlq/requeue")
                        .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queue\":\"mall.not.exist.dlq\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不支持的死信队列")));
    }

    // ==================== helpers ====================

    /** 通过 ping 接口读某个队列的消息数（顺带覆盖 ping 的自检字段）；后台身份固定取 adminHeaders()（P8-2a） */
    private long queueMessages(String key) {
        try {
            String body = mockMvc.perform(get("/api/admin/mq/ping").headers(adminHeaders()))
                    .andReturn().getResponse().getContentAsString();
            Number v = JsonPath.read(body, "$.data." + key + ".messages");
            return v == null ? -1 : v.longValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private void purgeDlqs() {
        for (String q : new String[]{MqTopology.DLQ, MqTopology.SYNC_DLQ, MqTopology.EVENT_DLQ,
                MqTopology.SYNC_QUEUE, MqTopology.SYNC_RETRY_QUEUE}) {
            try {
                amqpAdmin.purgeQueue(q);
            } catch (Exception ignored) {
                // 队列还没声明时忽略
            }
        }
    }

    private boolean rabbitUp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5672), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitUntil(long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
