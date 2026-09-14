package com.mall.search.mq;

import com.mall.search.service.ProductSearchService;
import com.mall.search.support.MqMessages;
import com.mall.search.support.MqTopology;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>MQ 消费侧语义套件（不连 broker）</b>：验"消息进来之后本服务怎么处理"。
 *
 * <h2>从哪来 / 降到单元级的原因（这一段是交付说明，不许省略）</h2>
 * 迁移自单体 {@code pms/ProductSyncMqMySqlTest}。原版三条用例都要真 broker + 真 MySQL：
 * <ol>
 *   <li>"下单扣库存 → 消息驱动索引更新"：<b>订单侧不在本服务</b>（markDirty 属 trade/单体），
 *       本服务能接的是**消费侧**，故搬到这里；</li>
 *   <li>"管理端上架 → 消息驱动写索引"：同上，写链路属 product；</li>
 *   <li><b>"毒消息重试到上限后进死信"</b>：原版依赖**真 broker 的 TTL 死信回路**
 *       （投到 {@code product.sync.retry} → 消息 TTL 到期 → 死信回 {@code product.sync}）。
 *       这个"到期自动回到工作队列"的机制发生在 <b>broker 内部</b>，用 mock **复现不了**。</li>
 * </ol>
 * 因此本类只验**本服务这一侧**的决策（成功 ack / 失败转重试 / 超限转死信 / 毒消息原样重投 /
 * 一切路径都 ack 不 requeue），把"broker 真的会把重试消息送回工作队列"这一环交给
 * {@code ProductSyncMqBrokerIntegrationTest}（真 broker，连不上会**具名跳过**）。
 *
 * <p>⚠️ 结论口径：**本类是单元级，它绿 ≠ 死信回路通**。两者都绿才算这一段验完。
 * 这句话必须一起报出去（否则"迁移完成"会被读成"全链路已验证"）。
 */
class ProductSyncConsumerTest {

    private ProductSearchService productSearchService;
    private ProductSyncPublisher publisher;
    private Channel channel;
    private ProductSyncConsumer consumer;

    @BeforeEach
    void setUp() {
        productSearchService = mock(ProductSearchService.class);
        publisher = mock(ProductSyncPublisher.class);
        channel = mock(Channel.class);
        consumer = new ProductSyncConsumer(productSearchService, publisher);
        // maxRetry 是 @Value 注入的字段；单元测试里直接置 1，让"超限"只需两条消息就能走到
        ReflectionTestUtils.setField(consumer, "maxRetry", 1);
    }

    @Test
    @DisplayName("[消费/成功] 全部同步成功 → ack 一次，不发重试、不进死信")
    void allSucceed_acksOnce() throws Exception {
        when(productSearchService.syncProducts(List.of(1L, 2L))).thenReturn(List.of());

        consumer.onProductSync(message(ProductSyncMessage.ofSpuIds(List.of(1L, 2L)), 0, 11L), channel);

        verify(channel, times(1)).basicAck(11L, false);
        verify(publisher, never()).publishRetry(any(), anyInt());
        verify(publisher, never()).publishToDlq(any(), anyInt(), any());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("[消费/批量] 一条多 id 消息只调**一次** syncProducts（整批 bulk+单次 refresh，不逐篇）")
    void multiIdMessage_callsBatchOnce() throws Exception {
        List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L);
        when(productSearchService.syncProducts(ids)).thenReturn(List.of());

        consumer.onProductSync(message(ProductSyncMessage.ofSpuIds(ids), 0, 18L), channel);

        // 逐条入口**不许**再被消费者碰：碰了就等于回到"N 篇 = N 次写 + N 次 refresh"（写放大）
        verify(productSearchService, times(1)).syncProducts(ids);
        verify(productSearchService, never()).syncProduct(anyLong());
        verify(channel, times(1)).basicAck(18L, false);
    }

    @Test
    @DisplayName("[消费/部分失败] 只把**失败的 id** 转重试（retry+1），成功的 id 不重投，原消息照旧 ack")
    void partialFailure_retriesOnlyFailedIds() throws Exception {
        when(productSearchService.syncProducts(List.of(1L, 2L))).thenReturn(List.of(2L));

        consumer.onProductSync(message(ProductSyncMessage.ofSpuIds(List.of(1L, 2L)), 0, 12L), channel);

        verify(publisher, times(1)).publishRetry(
                argThat(p -> p.spuIds().equals(List.of(2L))), eq(1));
        verify(publisher, never()).publishToDlq(any(), anyInt(), any());
        verify(channel, times(1)).basicAck(12L, false);
    }

    @Test
    @DisplayName("[消费/重试超限] retry 已达上限仍失败 → 进死信队列（带原因），原消息 ack 不堆积")
    void retryExhausted_goesToDlq() throws Exception {
        when(productSearchService.syncProducts(List.of(2L))).thenReturn(List.of(2L));

        // 头的重试次数=1、上限=1 ⇒ retry+1=2 > 1 ⇒ 死信
        consumer.onProductSync(message(ProductSyncMessage.ofSpuIds(List.of(2L)), 1, 13L), channel);

        verify(publisher, never()).publishRetry(any(), anyInt());
        verify(publisher, times(1)).publishToDlq(
                argThat(p -> p.spuIds().equals(List.of(2L))), eq(2),
                argThat(reason -> reason != null && reason.contains("上限")));
        verify(channel, times(1)).basicAck(13L, false);
    }

    @Test
    @DisplayName("[消费/异常] 业务异常(syncProducts 抛错) → 转重试 + ack，**绝不用 nack requeue**（会打成死循环）")
    void businessException_isRetriedNotRequeued() throws Exception {
        when(productSearchService.syncProducts(List.of(2L)))
                .thenThrow(new IllegalStateException("ES 抖了一下"));

        consumer.onProductSync(message(ProductSyncMessage.ofSpuIds(List.of(2L)), 0, 14L), channel);

        verify(publisher, times(1)).publishRetry(any(), eq(1));
        verify(channel, times(1)).basicAck(14L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("[消费/毒消息] 正文不是 JSON → **原样**重投（保留原 body），超限则原样进死信，且都 ack")
    void poisonMessage_isRawRetriedThenDeadLettered() throws Exception {
        Message poison = new Message("这不是 JSON".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new org.springframework.amqp.core.MessageProperties());
        poison.getMessageProperties().setDeliveryTag(15L);

        // 首次投递（无重试头）⇒ 原样重投一次
        consumer.onProductSync(poison, channel);
        verify(publisher, times(1)).publishRawRetry(eq(poison), eq(1),
                argThat(r -> r != null && r.startsWith("消息解析失败")));
        verify(publisher, never()).publishRawToDlq(any(), anyInt(), any());
        verify(channel, times(1)).basicAck(15L, false);

        // 重试头=1、上限=1 ⇒ 原样进死信
        Message poison2 = new Message("还是不是 JSON".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new org.springframework.amqp.core.MessageProperties());
        poison2.getMessageProperties().setDeliveryTag(16L);
        poison2.getMessageProperties().setHeader(MqMessages.HEADER_RETRY, 1);
        consumer.onProductSync(poison2, channel);
        verify(publisher, times(1)).publishRawToDlq(eq(poison2), eq(2), any());
        verify(channel, times(1)).basicAck(16L, false);
    }

    @Test
    @DisplayName("[消费/品牌] 品牌消息 → syncByBrand(brandId) 后 ack，不走 spuId 分支")
    void brandMessage_syncsByBrand() throws Exception {
        when(productSearchService.syncByBrand(937L)).thenReturn(45);

        consumer.onProductSync(message(ProductSyncMessage.ofBrand(937L), 0, 17L), channel);

        verify(productSearchService, times(1)).syncByBrand(937L);
        verify(productSearchService, never()).syncProduct(anyLong());
        verify(channel, times(1)).basicAck(17L, false);
    }

    @Test
    @DisplayName("[消费/拓扑口径] 监听的工作队列就是 mall.pms.es-sync（拓扑名与单体逐字一致，不许改名）")
    void listensOnTheSameQueueName() throws Exception {
        var annotation = org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                ProductSyncConsumer.class.getMethod("onProductSync", Message.class, Channel.class),
                org.springframework.amqp.rabbit.annotation.RabbitListener.class);
        assertTrue(annotation != null && annotation.queues().length == 1
                        && MqTopology.SYNC_QUEUE.equals(annotation.queues()[0]),
                "监听队列必须恒为 " + MqTopology.SYNC_QUEUE + "（改队列名会让过渡期两个消费者错开队列）");
    }

    // ---------- helpers ----------

    private Message message(ProductSyncMessage payload, int retry, long deliveryTag) {
        Map<String, Object> headers = retry > 0 ? Map.of(MqMessages.HEADER_RETRY, retry) : null;
        Message message = MqMessages.json(payload, null, "p6-2-test-" + deliveryTag, headers);
        message.getMessageProperties().setDeliveryTag(deliveryTag);
        return message;
    }
}
