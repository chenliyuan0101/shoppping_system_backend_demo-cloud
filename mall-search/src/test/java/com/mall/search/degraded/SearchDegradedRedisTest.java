package com.mall.search.degraded;

import com.jayway.jsonpath.JsonPath;
import com.mall.search.support.SearchTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>失败语义套件（其二）：Redis 不可达</b> —— 规格 §6 的第三行。
 *
 * <h2>这个套件在验什么</h2>
 * 把 Redis 指向**死端口**（{@code 127.0.0.1:1}），验"Redis 挂了的时候"本服务的承诺：
 * <ol>
 *   <li>{@code status} 如实报 {@code pendingCount=-1}（**不是 0**：0 的含义是"队列是空的、没有待同步"，
 *       那会让人以为"一切都同步好了"，而真相是"兜底通道根本不可用"）；</li>
 *   <li>{@code markDirty} **只告警、不抛异常** —— 订单/管理端链路是在事务里调它的，
 *       兜底通道故障绝不能把业务事务带崩（fail-open）；</li>
 *   <li>{@code drainPending} 返回**空集合**而不是抛异常（定时任务不该因为 Redis 挂了而炸栈）；</li>
 *   <li>**业务不受影响**：检索用的真 ES 仍然正常返回（Redis 只是"索引增量同步的兜底队列"）。</li>
 * </ol>
 *
 * <h2>同时关掉 MQ 的理由</h2>
 * §6 第三行的场景是"MQ 与 Redis 都不可用 ⇒ 只 log.warn"。MQ 在测试期本来就关
 * （{@code src/test/resources/application.properties}），这里再显式写一遍，
 * 让"两条兜底通道同时不可用"这个前提**在代码里看得见**，而不是靠读配置文件猜出来。
 *
 * <h2>本套件与"共享 Redis"的关系</h2>
 * 本套件**不碰**真 Redis（它连的是死端口），因此不会取走 {@code mall:es:pending} 里
 * 属于单体/其它套件的待同步标记 —— 这也是把"Redis 可用时的兜底写入"放在打桩层
 * （{@code ProductIndexContentStubbedEsTest}）而**不放在这里**的原因。
 */
@SpringBootTest(properties = {
        // 死端口 1（tcpmux）：本机不监听 ⇒ 连接立即被拒
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        // §6 第三行：MQ 也不可用（同时把测试期的默认口径写明白）
        "mall.mq.enabled=false"
})
@AutoConfigureMockMvc
class SearchDegradedRedisTest extends SearchTestSupport {

    private static final long FAKE_SPU_ID = FAKE_SPU_ID_BASE + 300;

    @Autowired
    private com.mall.search.service.ProductSearchService productSearchService;

    /** 自检：确认本用例的前提（MQ 确实关着）成立，避免"前提没生效却报绿" */
    @Value("${mall.mq.enabled:true}")
    private boolean mqEnabled;

    @Test
    @DisplayName("[失败语义/Redis 不可达] 前提自检：本上下文 MQ 确实关着（两条兜底通道同时不可用）")
    void premise_mqDisabled() {
        assertFalse(mqEnabled, "本套件的前提是 MQ 关闭（兜底通道 = Redis），MQ 开着就不是这个场景了");
    }

    @Test
    @DisplayName("[失败语义/Redis 不可达] status 如实报 pendingCount=-1（不是 0），且不误报 ES 故障")
    void status_redisDown_reportsPendingCountMinusOne() throws Exception {
        MvcResult r = mockMvc.perform(get("/internal/v1/search/status").header(TOKEN_HEADER, internalToken))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);

        assertEquals(0, codeOf(b), "自检接口本身必须能用");
        assertEquals(-1L, ((Number) JsonPath.read(b, "$.data.pendingCount")).longValue(),
                "⚠️ Redis 不可达必须报 -1；报 0 会被读成'队列已排空、全部同步完成'，正好相反");
        assertTrue(((Number) JsonPath.read(b, "$.data.docCount")).longValue() > 0,
                "本用例只降级 Redis（ES 正常），docCount 应当 > 0；<=0 说明 ES 也坏了，前提不成立");
    }

    @Test
    @DisplayName("[失败语义/MQ+Redis 都不可用] markDirty 只告警不抛异常（不许带崩业务事务）")
    void markDirty_bothFallbacksDown_onlyWarns() {
        assertDoesNotThrow(() -> productSearchService.markDirty(List.of(FAKE_SPU_ID)),
                "兜底通道故障必须 fail-open：调用方（订单/管理端）在事务里调它，抛异常会把业务写操作带崩");

        assertEquals(-1L, productSearchService.pendingCount(),
                "标记没能入队（Redis 不可用）⇒ pendingCount=-1；" +
                        "这种情况下这些商品靠**全量重建**兜底，这一点必须能从自检里看出来");
    }

    @Test
    @DisplayName("[失败语义/Redis 不可达] drainPending 返回空集合而不是抛异常（定时任务不许炸栈）")
    void drainPending_redisDown_returnsEmpty() {
        List<Long> drained = assertDoesNotThrow(() -> productSearchService.drainPending(100),
                "定时任务每轮都会 drain；Redis 挂了就抛异常会让调度器整轮中断，其它商品也不再同步");
        assertTrue(drained.isEmpty(), "取不到就该是空集合（fail-open），实际=" + drained);
    }

    @Test
    @DisplayName("[失败语义/Redis 不可达] mark-dirty 端点如实报 added=-1（不是 0）且不抛 —— 0 会被读成'已经标好了'")
    void markDirtyEndpoint_redisDown_reportsMinusOne() throws Exception {
        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/mark-dirty",
                        "{\"spuIds\":[" + FAKE_SPU_ID + "]}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);

        assertEquals(0, codeOf(b),
                "兜底通道故障不是接口错误（fail-open：调用方在事务里调它，报错会把业务写操作带崩）");
        assertEquals(-1L, ((Number) JsonPath.read(b, "$.data.added")).longValue(),
                "⚠️ Redis 不可达必须报 -1（与 status.pendingCount 的 -1 同一口径）；"
                        + "报 0 会被读成'已经标好了，等定时任务就行'，而真相是标记**根本没落下**");
    }

    @Test
    @DisplayName("[失败语义/Redis 不可达] 检索业务不受影响：真 ES 照常返回结果")
    void search_stillWorksWhileRedisIsDown() throws Exception {
        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/products",
                        "{\"pageNum\":1,\"pageSize\":5}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);

        assertEquals(0, codeOf(b), "Redis 只是增量同步的兜底队列，不该影响检索主链路");
        assertTrue(((Number) JsonPath.read(b, "$.data.total")).longValue() > 0,
                "索引里有文档（本用例只降级 Redis），检索应当有结果");
        assertFalse(((List<?>) JsonPath.read(b, "$.data.spuIds")).isEmpty(), "应当返回命中的 spuId 列表");
    }
}
