package com.mall.product.mq;

import com.mall.common.support.JsonKit;
import com.mall.product.support.MqTopology;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 商品索引同步**发布方**的单元测试（P6-5 #1）：用桩 {@code RabbitTemplate} 查"投出去的消息长什么样"。
 *
 * <h2>为什么这里可以是纯单测（不需要真 broker）</h2>
 * 本类要证的<b>不是</b>"broker 收得到"（那是活体验证的事：真 8102 + 真 broker + 真 ES，测"变更到索引可见"
 * 的毫秒数），而是三件**能被字节级检查**的事：
 * <ol>
 *   <li><b>契约字段名</b>：{@code spuIds} / {@code brandId} / {@code enqueuedAtMillis} —— 消费方
 *       （{@code mall-search} 的同名 record）靠 JSON 字段名对齐，改名不会有任何编译错误，只会在运行期变成
 *       "同步静默失效"。所以这里断言的是 {@code keySet} **完全相等**（多一个字段也算契约变化）。</li>
 *   <li><b>路由</b>：交换机 {@code mall.pms.sync} + 路由键 {@code product.sync}（投错键 = 消息进不了工作队列，
 *       同样静默）。</li>
 *   <li><b>事务边界</b>：{@code markDirty} 的调用方（{@code StockCommandServiceImpl}）在**事务内**调过来，
 *       消息必须在<b>提交之后</b>才投出去；回滚时**不投**。这条是 P6-4"幽灵索引文档"缺陷的正解，
 *       也是本类最容易在重构中被改坏的一条（所以单独三个用例盯它）。</li>
 * </ol>
 *
 * <p>真实投递路径（含"MQ 不可用时回落 Redis 待同步集合"）由 {@code ProductTestBase} 把
 * {@code mall.mq.enabled=false} 钉住 + 活体探针覆盖，见 {@code ProductSyncPublisher} 的类注释。
 */
class ProductSyncPublisherTest {

    private RabbitTemplate rabbitTemplate;
    private ProductSyncPublisher publisher;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        publisher = new ProductSyncPublisher(rabbitTemplate);
        ReflectionTestUtils.setField(publisher, "mqEnabled", true);   // 与生产默认值一致（matchIfMissing=true）
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ==================================================================
    // 契约：字段名 / 路由 / 分批
    // ==================================================================

    @Test
    @DisplayName("[MQ] 投递按 100 分批，且 JSON 字段名与路由键逐字符合契约（改名会静默失效，所以断言 keySet 相等）")
    void publishSpuIds_usesContractNamesAndBatches() {
        List<Long> ids = LongStream.rangeClosed(1, 250).boxed().toList();

        assertTrue(publisher.publishSpuIds(ids), "全部投递成功应返回 true");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate, times(3))          // 250 = 100 + 100 + 50
                .send(eq(MqTopology.SYNC_EXCHANGE), eq(MqTopology.SYNC_ROUTING_KEY), captor.capture());

        int[] expectedSizes = {100, 100, 50};
        List<Message> messages = captor.getAllValues();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> body = JsonKit.toObject(
                    new String(messages.get(i).getBody(), StandardCharsets.UTF_8), Map.class);
            // ⚠️ 实测到的契约细节（本用例第一次跑就红，值得记下来）：Jackson 把 record 的
            //    `boolean isBrand()` 也序列化成一个字段 `brand`（bean 风格的 getter 规则），
            //    所以**线上消息是 4 个字段**，不是 3 个。这不是本批引入的形状：
            //    单体与 search 的同名 record 都有 `isBrand()`，线上一直如此；
            //    而 search 侧的 ProductSyncConsumerTest 正是用 `MqMessages.json(自己那份 record)` 造消息
            //    并断言消费成功 ⇒ **两边都容忍这个多出来的字段**（证据：那套件是绿的）。
            //    所以本类**不**单方面"清理"字段：契约以线上实际字节为准，改它才是改契约。
            assertEquals(Set.of("spuIds", "brandId", "enqueuedAtMillis", "brand"), body.keySet(),
                    "第 " + i + " 条消息的 JSON 字段名（含 isBrand() 带出的 brand）");
            assertEquals(expectedSizes[i], ((List<?>) body.get("spuIds")).size(), "第 " + i + " 批的 id 数");
            assertNull(body.get("brandId"), "商品语义的消息 brandId 必须为 null（消费方据此区分两种语义）");
            assertTrue(((Number) body.get("enqueuedAtMillis")).longValue() > 0, "入队时间戳应被写入");
        }
        assertEquals("application/json", messages.get(0).getMessageProperties().getContentType());
        // 首投**不能**带重试头：消费方用"有没有 mall-retry-count"判断首次投递
        assertFalse(messages.get(0).getMessageProperties().getHeaders().containsKey("mall-retry-count"));
    }

    @Test
    @DisplayName("[MQ] 品牌语义：spuIds 为空、brandId 非空（消费方据此走 syncByBrand）")
    void publishBrand_usesBrandSemantics() {
        assertTrue(publisher.publishBrand(7L));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(MqTopology.SYNC_EXCHANGE), eq(MqTopology.SYNC_ROUTING_KEY), captor.capture());
        Map<String, Object> body = JsonKit.toObject(
                new String(captor.getValue().getBody(), StandardCharsets.UTF_8), Map.class);
        assertEquals(7, ((Number) body.get("brandId")).intValue());
        assertEquals(List.of(), body.get("spuIds"));
    }

    // ==================================================================
    // 事务边界（P6-4 幽灵文档那类缺陷的正解）
    // ==================================================================

    @Test
    @DisplayName("[MQ/事务] 事务内调用：提交前**不投递**，afterCommit 才投；回滚只调 afterCompletion ⇒ 不投")
    void publishSpuIds_defersUntilCommit() {
        List<Long> ids = List.of(2001L, 2002L);

        TransactionSynchronizationManager.initSynchronization();
        publisher.publishSpuIds(ids, () -> { });

        verifyNoInteractions(rabbitTemplate);      // ① 提交前：一条都不许出去
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size(), "应注册恰好 1 个事务同步回调");

        // ② 回滚路径：只回调 afterCompletion，不应投递（本类只挂 afterCommit）
        syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(rabbitTemplate);

        // ③ 提交路径：afterCommit 才真正投出去
        syncs.forEach(TransactionSynchronization::afterCommit);
        verify(rabbitTemplate, times(1))
                .send(eq(MqTopology.SYNC_EXCHANGE), eq(MqTopology.SYNC_ROUTING_KEY), any(Message.class));
    }

    // ==================================================================
    // 兜底：MQ 关闭 / 投递失败
    // ==================================================================

    @Test
    @DisplayName("[MQ/兜底] 投递抛异常 ⇒ 返回 false 且执行兜底；不带兜底的入口只返回 false（不抛给业务）")
    void publishSpuIds_fallsBackWhenSendThrows() {
        doThrow(new RuntimeException("broker 不可达")).when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class));
        AtomicInteger fallback = new AtomicInteger();

        assertFalse(publisher.publishSpuIds(List.of(1L)), "投递失败应返回 false（业务据此回落兜底通道）");
        publisher.publishSpuIds(List.of(2L), fallback::incrementAndGet);
        assertEquals(1, fallback.get(), "投递失败必须执行兜底动作（写 Redis 待同步集合），且只执行一次");
    }

    @Test
    @DisplayName("[MQ/兜底] mall.mq.enabled=false ⇒ 一条都不投，且立即（不需等事务）执行兜底")
    void mqDisabled_neverSendsAndRunsFallbackImmediately() {
        ReflectionTestUtils.setField(publisher, "mqEnabled", false);
        AtomicInteger fallback = new AtomicInteger();

        assertFalse(publisher.publishSpuIds(List.of(1L)), "MQ 关闭时应返回 false");
        assertFalse(publisher.publishBrand(7L), "MQ 关闭时品牌语义同样返回 false");

        List<String> ran = new ArrayList<>();
        publisher.publishSpuIds(List.of(1L), () -> ran.add("spuIds"));
        publisher.publishBrand(7L, () -> ran.add("brand"));
        assertEquals(List.of("spuIds", "brand"), ran, "MQ 关闭 ⇒ 兜底动作照常执行（这是既有路径，行为零变化）");
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("[MQ] 空/全 null 入参：不投 MQ；兜底动作会被调用一次（刻意与单体同名方法同形）")
    void emptyInput_isNoop() {
        AtomicInteger fallback = new AtomicInteger();
        assertFalse(publisher.publishSpuIds(List.of()));
        assertFalse(publisher.publishSpuIds(null));
        publisher.publishSpuIds(java.util.Arrays.asList(null, null), fallback::incrementAndGet);

        // ⚠️ 空输入仍会调用兜底动作 —— 这是**刻意与单体同名方法逐字同形**的行为
        //    （单体的 `publishSpuIds(ids, onFailure)` 也是 "!mqEnabled || ids.isEmpty() ⇒ 走兜底"）。
        //    两个真实调用方（RemoteProductSearchService 的 markDirty / syncLater）在**调用前**都已经
        //    过滤掉空集合 ⇒ 线上不会因此多写 Redis 标记；这里锁住的是"不投 MQ + 兜底最多一次"这条可观察契约。
        assertEquals(1, fallback.get(), "空输入不投 MQ，但兜底动作被调用一次（真实调用方已提前过滤）");
        verifyNoInteractions(rabbitTemplate);
    }
}
