package com.mall.search.content;

import com.jayway.jsonpath.JsonPath;
import com.mall.search.dto.IndexDocsResult;
import com.mall.search.dto.ProductSearchDoc;
import com.mall.search.support.BusinessException;
import com.mall.search.support.SearchTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>内容来源打桩层</b>：验"取到内容 → 写进 ES / 取不到 → 从 ES 删除"这条流水线，
 * **完全不依赖 mall-product**（用例级 {@code @MockitoBean} 换掉出站客户端）。
 *
 * <h2>为什么必须打桩（主 agent 2026-09-14 的返工根因）</h2>
 * 第一版把 `reindex`/`sync` 用例直接打到活着的 8102 上，于是：
 * ① 单测的成败取决于"外面是否正好跑着 product、令牌是否正好对得上"——**换个环境就红/绿漂移**；
 * ② 失败时抛出来的是**商品服务**的文案（`内部接口鉴权失败`），把 search 自己的问题盖住了。
 * 现在这一层验的是**search 自己的逻辑**：内容拿到后怎么写、写哪些字段、取不到时怎么办。
 * 真实的跨服务调用属于**显式集成层**（{@code SearchReindexIntegrationTest}）与主 agent 的活体脚本。
 *
 * <h2>数据怎么隔离</h2>
 * 用**假 spuId 号段**（9 开头，真实数据里不存在）：写完在 {@link #cleanUp()} 里删掉，
 * 并核对"文档数回到基线"——不许给共享索引留垃圾（历史上单体留下过孤儿文档 {@code _id=22030}）。
 */
class ProductIndexContentStubbedEsTest extends SearchTestBase {

    // ⚠️ 出站客户端 **不再在本类声明** @MockitoBean：桩已经由基类 {@code SearchTestBase} 提供
    //    （protected 字段 {@code productIndexDocClient}），本类直接用它安排返回值。
    //    这样"继承基类 ⇒ 默认 hermetic"是一条**结构性**保证，而不是每个套件各自记得加桩。

    /** 被测对象（**真实实现**，只把它的出站客户端换成基类提供的桩） */
    @Autowired
    private com.mall.search.service.ProductSearchService productSearchService;

    private final long fakeSpuId = FAKE_SPU_ID_BASE + 100;
    private final long fakeSpuId2 = FAKE_SPU_ID_BASE + 101;

    /** 前提自检用：本套件的环境里 MQ 必须是关的（"回落 Redis 兜底"那条断言靠它成立） */
    @Value("${mall.mq.enabled:true}")
    private boolean mqEnabled;

    @AfterEach
    void cleanUp() {
        deleteDocQuietly(fakeSpuId);
        deleteDocQuietly(fakeSpuId2);
        // ⚠️ 判据从"全局文档数回到基线"改成"**只关于我自己**的两条"：
        //    索引是共享的，别人一次全量重建（删索引→重建→写回）就会让全局计数在阶段间跳变，
        //    我 05:45 实测撞上过（当场 docCount=-1，报出来却是"测试留了垃圾"）。
        //    逐篇必须已删除 + 假号段必须清零 —— 比原来的全局计数更锐利，且不受第三方写入影响。
        assertNull(getDoc(fakeSpuId), "用例遗留了文档 " + fakeSpuId);
        assertNull(getDoc(fakeSpuId2), "用例遗留了文档 " + fakeSpuId2);
        assertEquals(0L, fakeSegmentDocCount(), "假号段（9 开头）必须清零，不许给共享索引留垃圾");
    }

    @Test
    @DisplayName("[打桩/单条同步] 取到内容 → 按 spuId 写入 ES，且 **12 个字段逐字落库**")
    void syncProduct_upsertsDocumentWithAllTwelveFields() throws Exception {
        ProductSearchDoc doc = fullDoc(fakeSpuId, "打桩商品·单条同步 " + suffix);
        when(productIndexDocClient.bySpuIds(any())).thenReturn(new IndexDocsResult(1373L, List.of(doc)));

        boolean ok = productSearchService.syncProduct(fakeSpuId);
        assertTrue(ok, "取到内容时单条同步应当成功");

        ProductSearchDoc stored = getDoc(fakeSpuId);
        assertNotNull(stored, "文档必须真的写进索引");
        assertEquals(fakeSpuId, stored.getSpuId());
        assertEquals(doc.getTitle(), stored.getTitle());
        assertEquals(doc.getSubtitle(), stored.getSubtitle());
        assertEquals(doc.getMainImage(), stored.getMainImage());
        assertEquals(doc.getCategoryId(), stored.getCategoryId());
        assertEquals(doc.getBrandId(), stored.getBrandId());
        assertEquals(doc.getBrandName(), stored.getBrandName());
        assertEquals(doc.getMinPrice(), stored.getMinPrice());
        assertEquals(doc.getTotalStock(), stored.getTotalStock());
        assertEquals(doc.getSales(), stored.getSales());
        assertEquals(doc.getStatus(), stored.getStatus());
        assertEquals(doc.getCreateTimeMillis(), stored.getCreateTimeMillis());
    }

    @Test
    @DisplayName("[打桩/下架语义] 内容为空（商品下架/删除）→ 该 spuId **从索引删除**，且不影响别的文档")
    void syncProduct_emptyContent_deletesDocument() throws Exception {
        // 先放一篇进去（模拟"曾经在架"）
        when(productIndexDocClient.bySpuIds(any()))
                .thenReturn(new IndexDocsResult(1373L, List.of(fullDoc(fakeSpuId, "打桩商品·将被下架"))));
        assertTrue(productSearchService.syncProduct(fakeSpuId));
        assertTrue(docExists(fakeSpuId), "前置条件：文档应当已在索引里");
        // 「别误删别人的」用**逐文档**判据（不比较全局文档数：那会被第三方的重建/写入扰动）
        Long realSpuId = anyRealSpuId();
        assertNotNull(realSpuId, "前置条件：索引里应当有真实文档");

        // 再让它"下架"（product 返回空集）
        when(productIndexDocClient.bySpuIds(any())).thenReturn(new IndexDocsResult(1373L, List.of()));
        boolean ok = productSearchService.syncProduct(fakeSpuId);

        assertTrue(ok, "下架也算同步成功（语义：已按最新状态落索引）");
        assertFalse(docExists(fakeSpuId), "下架/删除的商品必须从索引移除");
        assertTrue(docExists(realSpuId), "只应删自己那一篇：真实文档 " + realSpuId + " 必须还在");
    }

    @Test
    @DisplayName("[打桩/失败语义] product 不可达 → 返回 false，**但绝不能把索引文档删掉**（fail-safe）")
    void syncProduct_contentUnavailable_returnsFalseAndKeepsDocument() throws Exception {
        when(productIndexDocClient.bySpuIds(any()))
                .thenReturn(new IndexDocsResult(1373L, List.of(fullDoc(fakeSpuId, "打桩商品·拉不到内容时"))));
        assertTrue(productSearchService.syncProduct(fakeSpuId));

        // 内容源抛错（BusinessException 是客户端在传输失败/业务码非 0 时抛的）
        when(productIndexDocClient.bySpuIds(any()))
                .thenThrow(new BusinessException(500, "系统繁忙，请稍后重试"));
        boolean ok = productSearchService.syncProduct(fakeSpuId);

        assertFalse(ok, "拉不到内容时应当返回 false（失败要能被调用方看见）");
        assertTrue(docExists(fakeSpuId),
                "⚠️ 拉不到内容**不等于**商品已下架：绝不能顺手删除索引文档（那会让商品从检索里消失且不报错）");
    }

    @Test
    @DisplayName("[打桩/按品牌] 一次性取齐内容 → 逐个 upsert，返回同步条数（不再逐条回拉 ⇒ 无 N+1）")
    void syncByBrand_indexesAllDocsFromOneFetch() throws Exception {
        when(productIndexDocClient.byBrand(anyLong())).thenReturn(new IndexDocsResult(1373L, List.of(
                fullDoc(fakeSpuId, "打桩商品·品牌同步 A"),
                fullDoc(fakeSpuId2, "打桩商品·品牌同步 B"))));

        int synced = productSearchService.syncByBrand(937L);

        assertEquals(2, synced, "应当同步两篇");
        assertNotNull(getDoc(fakeSpuId), "第一篇必须落索引");
        assertNotNull(getDoc(fakeSpuId2), "第二篇必须落索引");
        // 关键：只调用了一次 byBrand（没有对每个 doc 再回拉内容）
        org.mockito.Mockito.verify(productIndexDocClient, org.mockito.Mockito.times(1)).byBrand(937L);
        org.mockito.Mockito.verify(productIndexDocClient, org.mockito.Mockito.never()).bySpuIds(any());
    }

    @Test
    @DisplayName("[打桩/按品牌] 品牌下没有在架商品 → synced=0，不动索引")
    void syncByBrand_noDocs_isZero() {
        when(productIndexDocClient.byBrand(anyLong())).thenReturn(new IndexDocsResult(1373L, List.of()));
        assertEquals(0, productSearchService.syncByBrand(99999999L), "没有商品可同步 ⇒ 0");
    }

    @Test
    @DisplayName("[打桩/阈值] 内容里的 spuId 为 null 的文档被跳过（不写、不计数）")
    void syncByBrand_skipsNullSpuId() {
        ProductSearchDoc bad = fullDoc(fakeSpuId, "打桩商品·没有 id");
        bad.setSpuId(null);
        when(productIndexDocClient.byBrand(anyLong())).thenReturn(new IndexDocsResult(1373L, List.of(bad)));
        assertEquals(0, productSearchService.syncByBrand(937L), "spuId 为 null 的文档必须被跳过");
        assertNull(getDoc(fakeSpuId), "不该被写成 " + fakeSpuId);
    }
    @Test
    @DisplayName("[失败语义/reindex 取数失败] 明确报 500，且**索引里原有文档一篇不动** —— 绝不写半截索引")
    void reindex_contentSourceFails_reportsError_andLeavesIndexUntouched() throws Exception {
        // 先自己造一篇文档（它就是"重建如果误动作就会被清掉"的证物）
        when(productIndexDocClient.bySpuIds(any()))
                .thenReturn(new IndexDocsResult(1373L, List.of(fullDoc(fakeSpuId, "失败语义·重建前存在的文档"))));
        assertTrue(productSearchService.syncProduct(fakeSpuId), "前置条件：先放一篇文档进索引");
        assertTrue(docExists(fakeSpuId), "前置条件：文档应当已在索引里");

        // 让"取数"这一步抛错（= product 不可达/报错）
        when(productIndexDocClient.page(anyLong(), anyLong()))
                .thenThrow(new BusinessException(500, "系统繁忙，请稍后重试"));

        MvcResult r = mockMvc.perform(
                        internalPost("/internal/v1/search/reindex", "{}"))
                .andExpect(status().isOk())
                .andReturn();
        String b = body(r);

        assertEquals(500, codeOf(b), "取数失败必须报业务错误码（不许静默成功）");
        assertTrue(((String) messageOf(b)).startsWith("商品索引重建失败"),
                "错误文案必须点明重建失败，实际=" + messageOf(b));
        assertNull(JsonPath.read(b, "$.data"),
                "失败时不得返回 ReindexResult（那会让人以为'重建跑过了'）");
        // ⚠️ 核心判据：重建的语义是"**先在内存取齐**、再删旧索引建新索引"，
        //    取数阶段失败 ⇒ 索引必须原封不动。下面这篇文档就是证物：
        //    如果实现是"先删索引再取数"，它会连同索引一起消失。
        //    （刻意**不**用"全局文档数 == 基线"：索引是共享的，第三方一次重建就会让计数跳变 ——
        //      我 05:45 就是这么误报过一次；逐文档判据只取决于本用例动没动它。）
        assertNotNull(getDoc(fakeSpuId), "取数失败时索引必须原封不动：重建前存在的文档仍在");
        assertEquals(1L, fakeSegmentDocCount(), "假号段文档数应当仍是我放进去的那 1 篇");
    }

    @Test
    @DisplayName("[失败语义/MQ 不可用] markDirty 回落 Redis 待同步集合（兜底通道真的能用）")
    void markDirty_mqDisabled_fallsBackToRedisPendingSet() {
        assertFalse(mqEnabled, "前提：测试上下文里 MQ 是关的（src/test/resources/application.properties）");
        long fake = FAKE_SPU_ID_BASE + 200;

        productSearchService.markDirty(List.of(fake));

        // 取走并核对（Redis 的 Set 只能 SPOP 取出，没有"只读单个成员"的生产 API）；
        // ⚠️ 这个集合是与**单体/其它套件共享**的，所以取出来之后要把"不是本用例的"成员**放回去**
        //    —— 否则那些商品的待同步标记被测试吃掉，它们的索引就不会更新了。
        List<Long> drained = productSearchService.drainPending(500);
        List<Long> others = drained.stream().filter(id -> id != fake).toList();
        if (!others.isEmpty()) {
            productSearchService.markDirty(others);
        }

        assertTrue(drained.contains(fake),
                "MQ 不可用时 markDirty 必须把 spuId 写进 Redis 兜底集合(mall:es:pending)，实际取出=" + drained);
    }

    @Test
    @DisplayName("[失败语义/取不满] product 报在架数 > 实际给出的文档数 → reindex 报 500「文档取不满」，且索引一篇不动")
    void reindex_cannotFetchAll_reportsShortfall_andLeavesIndexUntouched() throws Exception {
        // 前置：先放一篇文档当**证物**（"取不满"若被静默放过，重建会删索引并写一份残缺索引，这篇就没了）
        when(productIndexDocClient.bySpuIds(any()))
                .thenReturn(new IndexDocsResult(1373L, List.of(fullDoc(fakeSpuId, "取不满语义·重建前存在的文档"))));
        assertTrue(productSearchService.syncProduct(fakeSpuId), "前置条件：先放一篇文档进索引");
        assertEquals(1L, fakeSegmentDocCount(), "前置条件：假号段里应当正好这一篇");

        // 桩：product 报在架 5 篇，却只给出 2 篇（页不满 ⇒ 翻页停在第一页）
        when(productIndexDocClient.page(anyLong(), anyLong()))
                .thenReturn(new IndexDocsResult(5L, List.of(
                        fullDoc(FAKE_SPU_ID_BASE + 201, "取不满语义·第 1 篇"),
                        fullDoc(FAKE_SPU_ID_BASE + 202, "取不满语义·第 2 篇"))));

        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/reindex", "{}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);

        assertEquals(500, codeOf(b), "取不满必须**在代码里炸**（不许静默写一份残缺索引），body=" + b);
        // 断言**完整文案**（不是 contains）：我第一版在防呆里也写了"商品索引重建失败："前缀，
        // 而调用方的 catch 已经加过一次 ⇒ 出现"失败：失败：取不满"的重复前缀，从原始日志里才发现。
        // 断言整串才能钉住这类"文案被套两层"的回归。
        assertEquals("商品索引重建失败：文档取不满（2/5）", messageOf(b),
                "文案必须一眼看出是'取不满'且带分子分母（已取/在架），实际=" + messageOf(b));
        assertNull(JsonPath.read(b, "$.data"), "失败时不得返回 ReindexResult");

        // 关键：取数阶段在"删旧索引"之前 ⇒ 索引必须原封不动（证物还在）
        assertNotNull(getDoc(fakeSpuId), "取不满时索引必须原封不动：重建前存在的文档仍在");
        assertEquals(1L, fakeSegmentDocCount(), "假号段文档数应当仍是 1（那两篇残缺文档**不许**被写进来）");
    }

    // ---------- helpers ----------

    /** 造一份"12 个字段都填满"的索引文档（与 ES mapping 的 12 个字段一一对应） */
    private ProductSearchDoc fullDoc(long spuId, String title) {
        ProductSearchDoc doc = new ProductSearchDoc();
        doc.setSpuId(spuId);
        doc.setTitle(title);
        doc.setSubtitle("打桩副标题");
        doc.setMainImage("http://img/p6-2/stub-main.jpg");
        doc.setCategoryId(12L);
        doc.setBrandId(937L);
        doc.setBrandName("雪松");
        doc.setMinPrice(19900L);
        doc.setTotalStock(41);
        doc.setSales(7);
        doc.setStatus(1);
        doc.setCreateTimeMillis(1_789_000_000_000L);
        return doc;
    }
}
