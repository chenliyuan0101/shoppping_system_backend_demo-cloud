package com.mall.demo.oms.outbox;

import com.mall.demo.common.JsonKit;
import com.mall.demo.common.MqMessages;
import com.mall.demo.common.MqTopology;
import com.mall.demo.oms.domain.TradeOutbox;
import com.mall.demo.oms.mq.OrderEventMessage;
import com.mall.demo.oms.mq.OrderEventPublisher;
import com.mall.demo.support.MySqlTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P8-3 发件箱（本地消息表）的真库测试。
 *
 * <p>它守的是"**事件不会因为进程崩在投递前而丢失**"这条新性质，以及"**外部形状一个字不变**"这条硬约束：
 * <ol>
 *   <li>入箱与业务事务同生共死（回滚 ⇒ 无行；提交 ⇒ 有行）；</li>
 *   <li>存下来的正文与"直投路径"发出去的**字节完全相同**（消费方不需要任何改动）；</li>
 *   <li>提交后快路径真投递并标记已发送；投递失败留行 + 退避 + 超限放弃；</li>
 *   <li>定时重投只捡"待发且到期"的行（未来的不捡、已发的不捡）—— 这就是"重启后补发"的机制；</li>
 *   <li>悬挂对账能点出"入箱很久还没发出去"的行。</li>
 * </ol>
 *
 * <p>用 {@code @MockitoBean RabbitTemplate} 拦下真实投递：既能断言"发出去的字节"，又不依赖 broker 可用性。
 * （真实 broker 的端到端投递由 P8-3 的**杀进程补发演练**在活体上证明，见 `.dsh-notes/P8-3-report.md`。）
 */
@SpringBootTest(properties = {
        "mall.mq.enabled=true",
        // 本套件要测"自动重投"，所以显式打开（src/test/resources/application.properties 里对**所有**测试上下文
        // 默认关掉了它：否则别的上下文的定时任务会扫同一张表、与本套件的断言抢行）。
        "mall.mq.outbox.relay-enabled=true",
        "mall.mq.outbox.max-retry=2",
        "mall.mq.outbox.backoff-seconds=1",
        // ⚠️ 定时重投在测试期放到 1 小时：默认 2 秒一轮会**在用例中途**把待发行的重投掉，
        //    于是"投递失败后 retry_count=1"这类断言会看到 2（第一次失败 + 自动重投又失败一次）、
        //    甚至因为 max-retry=2 直接变成"已放弃"。这与 src/test/resources/application.properties 里
        //    mall.order.timeout-scan-interval-ms 的处理同理：要断言"谁做了什么"，就得让**自动**的那条
        //    路径静止；需要时由用例显式调用 relay.relay()（本套件就是这么做的）。
        "mall.mq.outbox.relay-interval-ms=3600000",
        "mall.mq.outbox.relay-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@DisplayName("P8-3 发件箱：事件先落库再投递")
class TradeOutboxMySqlTest extends MySqlTestBase {

    @MockitoBean
    RabbitTemplate rabbitTemplate;

    @Autowired
    OrderEventPublisher publisher;

    @Autowired
    TradeOutboxService outbox;

    @Autowired
    TradeOutboxRelay relay;

    @Autowired
    PlatformTransactionManager txManager;

    /** 每个用例用独立的 biz_key，互不干扰；用例结束按前缀清掉 */
    private String newBizKey() {
        return "p83-" + UUID.randomUUID().toString().substring(0, 11);
    }

    @AfterEach
    void cleanOutboxRows() {
        jdbcTemplate.update("DELETE FROM trade_outbox WHERE biz_key LIKE 'p83-%'");
    }

    private OrderEventMessage paidEvent(String orderNo) {
        return OrderEventMessage.paid(orderNo, null, 300000L);
    }

    @Test
    @DisplayName("入箱跟随业务事务：回滚 ⇒ 一行都不留")
    void rollbackLeavesNoRow() {
        String biz = newBizKey();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.executeWithoutResult(status -> {
                outbox.enqueue("order.paid", MqTopology.EVENT_ROUTING_PAID, biz, paidEvent(biz));
                throw new IllegalStateException("模拟业务失败 ⇒ 整个事务回滚");
            });
        } catch (IllegalStateException expected) {
            // 预期
        }
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trade_outbox WHERE biz_key = ?", Integer.class, biz);
        assertThat(n).as("业务回滚 ⇒ 事件不许留下（否则会发出'业务没成'的事件）").isZero();
    }

    @Test
    @DisplayName("存下来的正文与『直投路径』的字节逐字相同（消费方零改动）")
    void storedPayloadIsByteIdenticalToDirectPath() {
        String biz = newBizKey();
        OrderEventMessage event = paidEvent(biz);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status ->
                outbox.enqueue(event.eventType(), MqTopology.EVENT_ROUTING_PAID, biz, event));

        List<TradeOutbox> rows = outbox.findByBizKey(biz);
        assertThat(rows).hasSize(1);
        TradeOutbox row = rows.get(0);
        assertThat(row.getStatus()).isEqualTo(TradeOutbox.STATUS_PENDING);
        assertThat(row.getRoutingKey()).isEqualTo(MqTopology.EVENT_ROUTING_PAID);
        assertThat(row.getPayload()).isEqualTo(JsonKit.toJson(event));

        // 直投路径会长什么样（改造前就是这一串）：
        Message direct = MqMessages.json(event, null, biz, Map.of());
        // 重投路径从库里拿正文原样发：
        Message replayed = MqMessages.raw(row.getPayload(), row.getBizKey());
        assertThat(replayed.getBody()).isEqualTo(direct.getBody());
        assertThat(new String(replayed.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(new String(direct.getBody(), StandardCharsets.UTF_8));
        assertThat(replayed.getMessageProperties().getContentType())
                .isEqualTo(direct.getMessageProperties().getContentType());
        assertThat(replayed.getMessageProperties().getContentEncoding())
                .isEqualTo(direct.getMessageProperties().getContentEncoding());
        assertThat(replayed.getMessageProperties().getDeliveryMode())
                .isEqualTo(direct.getMessageProperties().getDeliveryMode());
        assertThat(replayed.getMessageProperties().getMessageId()).isEqualTo(biz);
    }

    @Test
    @DisplayName("提交后快路径投递：字节正确、行被标记已发送")
    void fastPathSendsAfterCommitAndMarksSent() {
        String biz = newBizKey();
        OrderEventMessage event = paidEvent(biz);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> publisher.publishAfterCommit(event));

        verify(rabbitTemplate, times(1)).send(eq(MqTopology.EVENT_EXCHANGE),
                eq(MqTopology.EVENT_ROUTING_PAID), any(Message.class));
        List<TradeOutbox> rows = outbox.findByBizKey(biz);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).as("快路径成功后必须是已发送").isEqualTo(TradeOutbox.STATUS_SENT);
        assertThat(rows.get(0).getSentAt()).isNotNull();
        assertThat(rows.get(0).getRetryCount()).isZero();
    }

    @Test
    @DisplayName("投递失败：行留在箱里 + 记错误 + 排下次重投（业务不受影响）")
    void sendFailureKeepsRowAndSchedulesRetry() {
        String biz = newBizKey();
        doThrow(new org.springframework.amqp.AmqpException("broker 挂了"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> publisher.publishAfterCommit(paidEvent(biz)));

        List<TradeOutbox> rows = outbox.findByBizKey(biz);
        assertThat(rows).hasSize(1);
        TradeOutbox row = rows.get(0);
        assertThat(row.getStatus()).isEqualTo(TradeOutbox.STATUS_PENDING);
        assertThat(row.getRetryCount()).isEqualTo(1);
        assertThat(row.getNextRetryAt()).isNotNull();
        assertThat(row.getLastError()).contains("broker 挂了");
    }

    @Test
    @DisplayName("定时重投：只捡『待发且到期』的行；未来到期的与已发的都不捡")
    void relayPicksOnlyDuePendingRows() {
        String bizDue = newBizKey();
        String bizFuture = newBizKey();
        String bizSent = newBizKey();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            outbox.enqueue("order.paid", MqTopology.EVENT_ROUTING_PAID, bizDue, paidEvent(bizDue));
            outbox.enqueue("order.paid", MqTopology.EVENT_ROUTING_PAID, bizFuture, paidEvent(bizFuture));
            outbox.enqueue("order.paid", MqTopology.EVENT_ROUTING_PAID, bizSent, paidEvent(bizSent));
        });
        // 一个排到未来、一个已经发过
        jdbcTemplate.update("UPDATE trade_outbox SET next_retry_at = DATE_ADD(NOW(), INTERVAL 600 SECOND) WHERE biz_key = ?", bizFuture);
        jdbcTemplate.update("UPDATE trade_outbox SET status = 1, sent_at = NOW() WHERE biz_key = ?", bizSent);

        relay.relay();

        assertThat(outbox.findByBizKey(bizDue).get(0).getStatus()).as("到期的要发出去").isEqualTo(TradeOutbox.STATUS_SENT);
        assertThat(outbox.findByBizKey(bizFuture).get(0).getStatus()).as("没到期的不能提前发").isEqualTo(TradeOutbox.STATUS_PENDING);
        assertThat(outbox.findByBizKey(bizSent).get(0).getStatus()).as("已发的不会重发").isEqualTo(TradeOutbox.STATUS_SENT);
    }

    @Test
    @DisplayName("杀进程补发的机制内核：快路径没跑过（fast-path=false 等价姿态）⇒ 重启后由重投补发")
    void relayReplaysRowsThatFastPathNeverSent() {
        String biz = newBizKey();
        // 等价于"入库了但快路径没来得及发"（进程随后崩掉）——直接入库、不调快路径
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status ->
                outbox.enqueue("order.paid", MqTopology.EVENT_ROUTING_PAID, biz, paidEvent(biz)));

        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
        assertThat(outbox.findByBizKey(biz).get(0).getStatus()).isEqualTo(TradeOutbox.STATUS_PENDING);

        relay.relay();   // ← 等价于"重启后第一轮定时重投"

        List<TradeOutbox> rows = outbox.findByBizKey(biz);
        assertThat(rows.get(0).getStatus()).isEqualTo(TradeOutbox.STATUS_SENT);
        assertThat(rows.get(0).getSentAt()).isNotNull();
        verify(rabbitTemplate, times(1)).send(eq(MqTopology.EVENT_EXCHANGE),
                eq(MqTopology.EVENT_ROUTING_PAID), any(Message.class));
    }

    @Test
    @DisplayName("标记已发送是幂等的：第二次调用返回 false（并发重投不会把同一行标记两次）")
    void markSentIsIdempotent() {
        String biz = newBizKey();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status ->
                outbox.enqueue("order.paid", MqTopology.EVENT_ROUTING_PAID, biz, paidEvent(biz)));
        Long id = outbox.findByBizKey(biz).get(0).getId();

        assertThat(outbox.markSent(id)).isTrue();
        assertThat(outbox.markSent(id)).isFalse();
    }

    @Test
    @DisplayName("重试超限 ⇒ 进『已放弃』(status=2)，不再重投")
    void exceedingMaxRetryAbandonsTheRow() {
        String biz = newBizKey();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status ->
                outbox.enqueue("order.paid", MqTopology.EVENT_ROUTING_PAID, biz, paidEvent(biz)));
        Long id = outbox.findByBizKey(biz).get(0).getId();

        outbox.markFailed(id, "第一次失败");   // max-retry=2（见类上的 properties）
        assertThat(outbox.findByBizKey(biz).get(0).getStatus()).isEqualTo(TradeOutbox.STATUS_PENDING);
        outbox.markFailed(id, "第二次失败");
        TradeOutbox row = outbox.findByBizKey(biz).get(0);
        assertThat(row.getStatus()).as("超过上限必须是已放弃").isEqualTo(TradeOutbox.STATUS_ABANDONED);
        assertThat(row.getRetryCount()).isEqualTo(2);

        relay.relay();
        assertThat(outbox.findByBizKey(biz).get(0).getStatus())
                .as("已放弃的行不该被重投").isEqualTo(TradeOutbox.STATUS_ABANDONED);
    }

    @Test
    @DisplayName("悬挂对账：入箱超阈值仍未发出的行能被点出来")
    void reconciliationFindsHangingRows() {
        String biz = newBizKey();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status ->
                outbox.enqueue("order.paid", MqTopology.EVENT_ROUTING_PAID, biz, paidEvent(biz)));
        Long id = outbox.findByBizKey(biz).get(0).getId();

        assertThat(outbox.countHanging(300)).as("刚入箱的不算悬挂").isZero();
        jdbcTemplate.update("UPDATE trade_outbox SET created_at = DATE_SUB(NOW(), INTERVAL 900 SECOND) WHERE id = ?", id);
        assertThat(outbox.countHanging(300)).as("入箱 15 分钟没发出去 ⇒ 必须被点出来").isEqualTo(1);

        relay.reconcile();   // 触发一次对账（本条断言的是它不会抛异常，告警内容看日志）
        assertThat(outbox.countHanging(300)).isEqualTo(1);
    }

    @Test
    @DisplayName("P8-4 归档：只删『已发送且过期』的行；待发送/已放弃/未到期的都不动")
    void archiveDeletesOnlyOldSentRows() {
        String bizOldSent = newBizKey();
        String bizNewSent = newBizKey();
        String bizOldPending = newBizKey();
        String bizOldAbandoned = newBizKey();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            outbox.enqueue("order.paid", MqTopology.EVENT_ROUTING_PAID, bizOldSent, paidEvent(bizOldSent));
            outbox.enqueue("order.paid", MqTopology.EVENT_ROUTING_PAID, bizNewSent, paidEvent(bizNewSent));
            outbox.enqueue("order.paid", MqTopology.EVENT_ROUTING_PAID, bizOldPending, paidEvent(bizOldPending));
            outbox.enqueue("order.paid", MqTopology.EVENT_ROUTING_PAID, bizOldAbandoned, paidEvent(bizOldAbandoned));
        });
        // 两条"已发送"：一条 10 天前发的（该删）、一条刚发的（保留）
        jdbcTemplate.update("UPDATE trade_outbox SET status = 1, sent_at = DATE_SUB(NOW(), INTERVAL 10 DAY) WHERE biz_key = ?", bizOldSent);
        jdbcTemplate.update("UPDATE trade_outbox SET status = 1, sent_at = NOW() WHERE biz_key = ?", bizNewSent);
        // 一条待发送但入箱很久（**不删**：没了结的事归对账）、一条已放弃但很老（**不删**：等人工）
        jdbcTemplate.update("UPDATE trade_outbox SET created_at = DATE_SUB(NOW(), INTERVAL 30 DAY) WHERE biz_key = ?", bizOldPending);
        jdbcTemplate.update("UPDATE trade_outbox SET status = 2, created_at = DATE_SUB(NOW(), INTERVAL 30 DAY) WHERE biz_key = ?", bizOldAbandoned);

        int deleted = outbox.archiveSent(7, 1000);
        assertThat(deleted).as("只应删掉那一条 10 天前发出去的").isEqualTo(1);
        assertThat(outbox.findByBizKey(bizOldSent)).as("过期的已发送行应被归档").isEmpty();
        assertThat(outbox.findByBizKey(bizNewSent)).as("刚发出去的不该删").hasSize(1);
        assertThat(outbox.findByBizKey(bizOldPending)).as("待发送的绝不能当垃圾清掉").hasSize(1);
        assertThat(outbox.findByBizKey(bizOldAbandoned)).as("已放弃的要留给人看").hasSize(1);
        relay.archive();   // 再调一次定时入口（幂等：没有可删的就删 0 行）
        assertThat(outbox.findByBizKey(bizOldPending)).hasSize(1);
    }

    @Test
    @DisplayName("关单事件（自定义载荷 order.closed）也走发件箱，正文与直投一致")
    void rawPayloadPathAlsoGoesThroughOutbox() {
        String biz = newBizKey();
        var payload = new com.mall.demo.oms.mq.OrderClosedMessage(biz, 4242L, 77L, com.mall.demo.oms.mq.OrderClosedMessage.REASON_CANCEL, System.currentTimeMillis());
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status ->
                publisher.publishRawAfterCommit(MqTopology.EVENT_ROUTING_CLOSED, biz, payload));

        List<TradeOutbox> rows = outbox.findByBizKey(biz);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPayload()).isEqualTo(JsonKit.toJson(payload));
        assertThat(rows.get(0).getStatus()).isEqualTo(TradeOutbox.STATUS_SENT);
        verify(rabbitTemplate, times(1)).send(eq(MqTopology.EVENT_EXCHANGE),
                eq(MqTopology.EVENT_ROUTING_CLOSED), any(Message.class));
    }
}
