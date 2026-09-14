package com.mall.search.content;

import com.jayway.jsonpath.JsonPath;
import com.mall.search.dto.IndexDocsResult;
import com.mall.search.dto.ProductSearchDoc;
import com.mall.search.support.SearchTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>增量同步收敛套件（内容打桩 + 真 ES）</b>：从"要同步一个商品"到"ES 里能搜到它"这一段。
 *
 * <h2>从哪来</h2>
 * 迁移自单体 {@code pms/ElasticsearchIncrementSyncTest}（它的 {@code syncUntil} / {@code drainIndexSyncQueue}
 * 那套"多轮收敛"驱动）。单体那版验的是**订单/退款链路**改了库存销量之后索引跟着收敛 ——
 * 那些链路（下单、支付、确认收货、退款）**都不在本服务**（属 trade/单体），
 * 能在本服务验的只有：<b>"拿到内容 → 落索引 → 立刻可检索"</b>以及
 * <b>"兜底队列（Redis）→ 定时任务消费 → 收敛"</b>。
 *
 * <h2>刻意保留的两条硬断言</h2>
 * <ol>
 *   <li><b>索引写入后"立即可检索"</b>：ES 的写入默认要等 refresh（1s）才对 search 可见。
 *       索引同步若丢掉 refresh，会表现为"上架了但搜不到" —— 这种 bug 在单体时代被
 *       {@code Refresh.WaitFor} 挡住了，拆分后**必须继续挡住**（本套件用真 search 验它）；</li>
 *   <li><b>重复同步幂等</b>：文档 _id = spuId，反复同步同一商品**不得**产生重复文档
 *       （否则搜索结果里会出现同一个商品多次）。</li>
 * </ol>
 *
 * <h2>共享 Redis 的注意事项（重要）</h2>
 * 兜底集合 {@code mall:es:pending} 是**单体、活着的 mall-search、本套件共用的**。
 * 因此本套件：① 只处理**自己那个假 spuId**；② 把取出来的**别人的 id 原样放回**集合
 * （不还回去会让那些商品的待同步标记被测试吃掉 —— 这是"测试干扰在跑的服务"的典型）。
 */
class ProductIndexIncrementFallbackEsTest extends SearchTestBase {

    private static final long FALLBACK_SPU = FAKE_SPU_ID_BASE + 400;
    private static final long IDEMPOTENT_SPU = FAKE_SPU_ID_BASE + 401;

    @Autowired
    private com.mall.search.service.ProductSearchService productSearchService;

    @AfterEach
    void cleanUp() {
        deleteDocQuietly(FALLBACK_SPU);
        deleteDocQuietly(IDEMPOTENT_SPU);
        assertNull(getDoc(FALLBACK_SPU), "用例遗留了文档 " + FALLBACK_SPU);
        assertNull(getDoc(IDEMPOTENT_SPU), "用例遗留了文档 " + IDEMPOTENT_SPU);
        assertEquals(0L, fakeSegmentDocCount(), "假号段（9 开头）必须清零，不许给共享索引留垃圾");
    }

    @BeforeEach
    void clearOwnDocs() {
        deleteDocQuietly(FALLBACK_SPU);
        deleteDocQuietly(IDEMPOTENT_SPU);
    }

    @Test
    @DisplayName("[增量/收敛] 内容变了 → 同步后**立刻**能按标题检索到（索引写入的 refresh 语义）")
    void syncProduct_isImmediatelySearchable() throws Exception {
        String marker = "zzfb" + suffix;   // 唯一词元：只可能命中本用例造的这一篇
        when(productIndexDocClient.bySpuIds(any()))
                .thenReturn(new IndexDocsResult(1373L, List.of(doc(FALLBACK_SPU, marker, 3))));

        assertTrue(productSearchService.syncProduct(FALLBACK_SPU), "同步应当成功");

        // 关键：**不做任何等待/重试**，立刻检索
        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/products",
                        "{\"keyword\":\"" + marker + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b), "检索应当成功，body=" + b);
        assertEquals(1, ((Number) JsonPath.read(b, "$.data.total")).intValue(),
                "刚同步的商品必须**立刻**可检索（写入没 refresh 的话这里会是 0："
                        + "表现成'上架了却搜不到'）");
        assertEquals(FALLBACK_SPU, ((Number) JsonPath.read(b, "$.data.spuIds[0]")).longValue());
    }

    @Test
    @DisplayName("[增量/兜底通道] markDirty → 定时任务的 drain → 同步 → 索引收敛（失败的 id 会重新入队）")
    void fallbackChannel_drainThenSync_converges() {
        when(productIndexDocClient.bySpuIds(any()))
                .thenReturn(new IndexDocsResult(1373L, List.of(doc(FALLBACK_SPU, "zzfb" + suffix, 9))));

        productSearchService.markDirty(List.of(FALLBACK_SPU));

        // 模拟 ProductSearchSyncTask.flushPending() 的一轮（不依赖 15s 调度：那是环境节奏，不是语义）
        List<Long> pending = productSearchService.drainPending(500);
        // ⚠️ 共享集合：把别人的待同步标记**放回去**（本用例只负责自己那个假 spuId）
        List<Long> others = pending.stream().filter(id -> id != FALLBACK_SPU).toList();
        if (!others.isEmpty()) {
            productSearchService.markDirty(others);
        }
        assertTrue(pending.contains(FALLBACK_SPU),
                "markDirty 写的 id 必须能被打包取出（兜底通道不通 ⇒ MQ 挂掉时索引就永久停更）");

        List<Long> failed = new java.util.ArrayList<>();
        for (Long spuId : pending) {
            if (spuId == FALLBACK_SPU && !productSearchService.syncProduct(spuId)) {
                failed.add(spuId);
            }
        }
        assertEquals(List.of(), failed, "本轮不该有失败的 id（有失败会重新入队、留到下一轮）");
        assertNotNull(getDoc(FALLBACK_SPU), "走过兜底通道之后，索引里必须有这篇文档");
    }

    @Test
    @DisplayName("[增量/幂等] 反复同步同一商品：内容按最新值覆盖，且**不会产生重复文档**")
    void repeatedSync_isIdempotent() {
        when(productIndexDocClient.bySpuIds(any()))
                .thenReturn(new IndexDocsResult(1373L, List.of(doc(IDEMPOTENT_SPU, "zzidem" + suffix, 1))));
        assertTrue(productSearchService.syncProduct(IDEMPOTENT_SPU));
        assertEquals(1, getDoc(IDEMPOTENT_SPU).getSales(), "第一次同步写入 sales=1");

        // 内容变了（销量 1 → 2）再同步一次
        when(productIndexDocClient.bySpuIds(any()))
                .thenReturn(new IndexDocsResult(1373L, List.of(doc(IDEMPOTENT_SPU, "zzidem" + suffix, 2))));
        assertTrue(productSearchService.syncProduct(IDEMPOTENT_SPU));

        assertEquals(2, getDoc(IDEMPOTENT_SPU).getSales(), "重复同步必须按最新内容覆盖");
        assertEquals(1L, fakeSegmentDocCount(),
                "同一 spuId 反复同步只能有 1 篇文档（_id=spuId 才是幂等的保证；"
                        + "多出来就说明写成了不同 _id，搜索会出现重复商品）");
    }

    /** 造一篇内容源返回的文档 */
    private ProductSearchDoc doc(long spuId, String marker, int sales) {
        ProductSearchDoc doc = new ProductSearchDoc();
        doc.setSpuId(spuId);
        doc.setTitle(marker + " 增量收敛语料");
        doc.setSubtitle("增量语料");
        doc.setMainImage("http://img/p6-2/inc.jpg");
        doc.setCategoryId(12L);
        doc.setBrandId(937L);
        doc.setBrandName("雪松");
        doc.setMinPrice(9_900L);
        doc.setTotalStock(7);
        doc.setSales(sales);
        doc.setStatus(1);
        doc.setCreateTimeMillis(1_789_000_100_000L);
        return doc;
    }
}
