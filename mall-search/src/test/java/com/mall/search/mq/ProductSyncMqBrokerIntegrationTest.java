package com.mall.search.mq;

import com.mall.search.client.ProductIndexDocClient;
import com.mall.search.dto.IndexDocsResult;
import com.mall.search.support.MqMessages;
import com.mall.search.support.MqTopology;
import com.mall.search.support.SearchTestSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * <b>MQ 真 broker 集成套件</b>：验"消息真的经 RabbitMQ 走了一趟"，包括
 * <b>毒消息 → 重试队列 → TTL 到期回到工作队列 → 超限进死信</b>这一整条回路。
 *
 * <h2>为什么必须真 broker（不能 mock）</h2>
 * 死信回路的机制是"消息投到 {@code product.sync.retry} 队列 → 消息 TTL 到期 →
 * broker 把它死信回 {@code product.sync}"。这两次 broker 往返 + TTL 计时**全在 broker 内部**，
 * 用 mock 复现不了"到期自动回到工作队列"。单元级的 {@code ProductSyncConsumerTest} 只验了
 * "失败时会调 publishRetry/publishToDlq"，**回路本身没验** —— 两份合起来才算这段验完。
 *
 * <h2>已知干扰（说清楚，不许含糊）</h2>
 * 过渡期 {@code mall.pms.es-sync} 上**不止一个消费者**（单体 + 活着的 mall-search + 本用例所在的
 * 测试上下文）。所以：
 * <ul>
 *   <li>本套件**不假设**"是我这个消费者处理了消息" —— 断言只放在**队列/死信队列的可观测状态**上，
 *       谁消费都成立；</li>
 *   <li>"工作队列/重试队列回到 0"这类断言可能被**别人的在途消息**扰动（本项目当前是静置环境，
 *       实测稳定；一旦抖动会在报告里明说，而不是改成"只断言涨了"来掩盖）；</li>
 *   <li>每次运行会给 {@code .dlq} **留 1 条毒消息**（这正是死信队列的用途：留着当证据）。
 *       要清空：RabbitMQ 管理台，或 {@code rabbitmqctl purge_queue mall.pms.es-sync.dlq}。</li>
 * </ul>
 *
 * <h2>跳过口径</h2>
 * broker 不在（5672 连不上）⇒ {@code assume} 跳过，且**跳过原因里写明探的是哪个地址**
 * —— 跳过 = 这段没验，必须在报告里单列（不许混进"通过"）。
 */
@SpringBootTest(properties = {
        "mall.mq.enabled=true",              // 本套件就是验 MQ 的，显式打开（测试期默认关闭）
        "mall.mq.sync-retry-delay-ms=200",   // 把重试延迟压到 200ms，否则要等 10s
        "mall.mq.sync-max-retry=1"           // 重试 1 次就进死信，缩短用例时长
})
@AutoConfigureMockMvc
class ProductSyncMqBrokerIntegrationTest extends SearchTestSupport {

    /**
     * broker 的探活地址**必须与客户端用的是同一个配置值**（{@code spring.rabbitmq.host/port}）。
     *
     * <p>⚠️ 第一版这里写死了 {@code 127.0.0.1:5672}，于是"探针说可达"与"客户端实际连哪儿"
     * 可以不一致 —— 把 broker 端口指到别处时探针照样说"可达"，用例就会去连一个连不上的地址，
     * 表现成**失败**而不是**具名跳过**（探针自己骗人的老毛病）。现在改成读配置，
     * 并且可以用 {@code -Dspring.rabbitmq.port=<死端口>} 机械地验证"跳过这条路真的走得通"。
     */
    @Value("${spring.rabbitmq.host:127.0.0.1}")
    private String rabbitHost;

    @Value("${spring.rabbitmq.port:5672}")
    private int rabbitPort;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    /**
     * 内容源仍打桩：本套件验的是"MQ 回路"，不该被"product 是否正好在跑"污染。
     * 返回空集 ⇒ 消费侧把该 spuId 当作"已下架"并删除索引文档（不存在的假号段 ⇒ 无事发生），
     * 消费成功 ⇒ 正常 ack。
     */
    @MockitoBean
    private ProductIndexDocClient productIndexDocClient;

    @BeforeEach
    void requireBroker() {
        boolean up = rabbitUp();
        System.out.println("[MQ 集成层探活] host=" + rabbitHost + ":" + rabbitPort + " reachable=" + up);
        Assumptions.assumeTrue(up,
                "RabbitMQ 未启动（探活地址=" + rabbitHost + ":" + rabbitPort + "），跳过 MQ 回路用例");
        when(productIndexDocClient.bySpuIds(any())).thenReturn(new IndexDocsResult(0L, List.of()));
    }

    @Test
    @DisplayName("[MQ 集成/正常消息] 投一条合法同步消息 → 被消费掉（工作队列与重试队列归零），不进死信")
    void validMessage_isConsumedAndAcked() {
        long dlqBefore = queueDepth(MqTopology.SYNC_DLQ);

        // 假号段 spuId：真实数据里不存在，谁消费都不会写坏真实商品的索引
        rabbitTemplate.send(MqTopology.SYNC_EXCHANGE, MqTopology.SYNC_ROUTING_KEY,
                MqMessages.json(ProductSyncMessage.ofSpuIds(List.of(FAKE_SPU_ID_BASE + 500)),
                        null, "p6-2-valid", null));

        assertTrue(waitUntil(() -> queueDepth(MqTopology.SYNC_QUEUE) == 0
                        && queueDepth(MqTopology.SYNC_RETRY_QUEUE) == 0, 15_000L),
                "合法消息必须被消费并 ack，工作/重试队列应当归零；实际 工作="
                        + queueDepth(MqTopology.SYNC_QUEUE) + " 重试=" + queueDepth(MqTopology.SYNC_RETRY_QUEUE));
        assertEquals(dlqBefore, queueDepth(MqTopology.SYNC_DLQ),
                "合法消息**不许**进死信队列（进了说明正常路径被判成失败）");
    }

    @Test
    @DisplayName("[MQ 集成/毒消息] 解析不了的消息 → 重试队列(TTL) → 超限进死信，原消息 ack 不堆积")
    void poisonMessage_loopsThroughRetryQueueThenDiesInDlq() {
        long dlqBefore = queueDepth(MqTopology.SYNC_DLQ);

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        props.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send(MqTopology.SYNC_EXCHANGE, MqTopology.SYNC_ROUTING_KEY,
                new Message("这不是 JSON（P6-2 毒消息用例）".getBytes(StandardCharsets.UTF_8), props));

        // ① 最终必须出现在死信队列里（本套件的核心：TTL 回路真的会把它送回工作队列，
        //    再被判超限 → 进死信）
        assertTrue(waitUntil(() -> queueDepth(MqTopology.SYNC_DLQ) > dlqBefore, 25_000L),
                "毒消息最终必须进死信队列（重试 TTL 到期回到工作队列 → 超限 → 死信）；"
                        + "死信深度 before=" + dlqBefore + " after=" + queueDepth(MqTopology.SYNC_DLQ));
        // ② 原消息不许堆在队列里（每次失败都 ack 掉，靠重投/死信交接）
        assertTrue(waitUntil(() -> queueDepth(MqTopology.SYNC_QUEUE) == 0
                        && queueDepth(MqTopology.SYNC_RETRY_QUEUE) == 0, 10_000L),
                "原消息必须被 ack（不许靠 nack requeue 死循环），实际 工作="
                        + queueDepth(MqTopology.SYNC_QUEUE) + " 重试=" + queueDepth(MqTopology.SYNC_RETRY_QUEUE));
    }

    // ---------- helpers ----------

    /** 直接问 broker 要队列深度（不走单体那个 /api/admin/mq/ping） */
    private long queueDepth(String queue) {
        try {
            var info = amqpAdmin.getQueueInfo(queue);
            return info == null ? -1L : info.getMessageCount();
        } catch (Exception e) {
            return -1L;
        }
    }

    private boolean waitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();   // 超时后再判一次（避免"刚好在最后一刻满足"被误判）
    }

    /** broker 探活：裸 TCP（不依赖任何客户端库的重试/超时逻辑，快且明确）；地址取自**配置** */
    private boolean rabbitUp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(rabbitHost, rabbitPort), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
