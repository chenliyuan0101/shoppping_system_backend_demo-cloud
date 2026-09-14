package com.mall.search.integration;

import com.jayway.jsonpath.JsonPath;
import com.mall.search.client.ProductIndexDocClient;
import com.mall.search.dto.IndexDocsResult;
import com.mall.search.dto.ProductSearchDoc;
import com.mall.search.support.SearchTestSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>跨服务集成层</b>（显式声明依赖活着的 `mall-product`）。
 *
 * <h2>⚠️ 为什么它**不继承** {@code SearchTestBase}</h2>
 * 基类 `SearchTestBase` 是**默认 hermetic** 的（把出站客户端 {@code ProductIndexDocClient} 换成桩）。
 * 本类要验的恰恰是"内容真的从 product 拉过来"这条跨服务链路，**必须**用真客户端 ——
 * 所以它只继承不带 Spring 注解的 {@link SearchTestSupport}（拿到辅助方法），
 * 自己声明测试上下文，等于**显式退出 hermetic**。这样"谁碰网络"在类声明上就一目了然，
 * 不会出现"以为在测集成、其实对着桩跑"的静默退让（P5 禁的那类）。
 *
 * <h2>两条纪律</h2>
 * <ul>
 *   <li><b>连不上就跳过，不许失败</b>：{@code assumeTrue(productReachable(), "mall-product 未启动，跳过集成用例（探活地址=…）")}。
 *       跳过 = **这条没验**，必须在报告里单列并说明（不能混进总数不解释）；</li>
 *   <li><b>不要跨阶段比较全局计数</b>：本索引是共享的（单体在检索、主 agent 的验证脚本也在打它），
 *       一次 `before/after` 的 `_count` 比较会被任何第三方写入扰动 ⇒ **结构性易红**（我实测红过一次）。
 *       要断言"没多出文档"就用**逐文档**判据（`spuId=X 的文档数恰好 1`），比数总数更锐利。</li>
 * </ul>
 *
 * <p>基址来自 {@code mall.product.base-url}：**默认 `http://127.0.0.1:8102`**（直连，让"打的是哪个实例"一目了然），
 * 并支持用**与生产同一个环境变量** `MALL_PRODUCT_BASE_URL` 覆盖 —— 这不是"测试专用开关"，
 * 而是 application-dev.yaml 里那个占位符的同一个变量名；它的用途是让"连不上"这条路**可受控复现**。
 */
@SpringBootTest(properties = "mall.product.base-url=${MALL_PRODUCT_BASE_URL:http://127.0.0.1:8102}")
@AutoConfigureMockMvc
class SearchReindexIntegrationTest extends SearchTestSupport {

    /**
     * 真实的出站客户端（**不是桩**）：本类要验的正是跨服务取内容这条链路。
     * 它的 base-url 由本类的 {@code @SpringBootTest(properties=...)} 指向活着的 product。
     */
    @Autowired
    private ProductIndexDocClient productIndexDocClient;

    @BeforeEach
    void requireLiveProduct() {
        boolean up = productReachable();
        // 把"探的是哪个地址、结果如何"打出来：跳过时必须能从输出里看出**它到底探了什么**，
        // 否则"placeholder 没解析出来 ⇒ 探针必然失败"这类问题会伪装成合法跳过（探针自己骗人的老毛病）。
        System.out.println("[集成层探活] base-url=" + productBaseUrl + " reachable=" + up);
        Assumptions.assumeTrue(up,
                "mall-product 未启动，跳过集成用例（探活地址=" + productBaseUrl + "）");
    }

    @Test
    @DisplayName("[集成/reindex] 全量重建：indexed == 在架数 == ES 实测文档数（内容真的来自 product）")
    void reindex_isConsistentWithShelfCount() throws Exception {
        // 重建前先记住一篇真实文档，用于"重建后内容仍在"的核对（**跨阶段比较全局计数是不安全的**，见下）
        ProductSearchDoc before = anyIndexedDoc();
        assertNotNull(before, "索引里应当至少有一篇真实文档");

        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/reindex", "{}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b), "reindex 应当业务成功");

        String index = JsonPath.read(b, "$.data.index");
        long indexed = ((Number) JsonPath.read(b, "$.data.indexed")).longValue();
        long onShelf = ((Number) JsonPath.read(b, "$.data.onShelfTotal")).longValue();
        String analyzer = JsonPath.read(b, "$.data.titleAnalyzer");

        assertEquals(INDEX, index, "索引名必须保持不变（不改名、不加别名）");
        assertEquals("smartcn", analyzer, "分词器自检值应为 smartcn");
        assertTrue(onShelf > 0, "在架商品数应当 > 0（由 product 供给）");
        // ⚠️ 这条同时是"取不满就炸"那道新防呆（fetchAllDocs 末尾的 `sink.size() < onShelfTotal` → 500）
        //    在**集成层**的覆盖：真实数据下取数必须取齐，否则 reindex 会直接 500（上面第 77 行就会红），
        //    根本走不到这里。反过来说：本行绿 ⇒ 真实数据下没有误炸。桩层那条（total=5/docs=2）验的是"该炸时会炸"。
        assertEquals(onShelf, indexed, "写入数必须等于在架数（不等说明有写入失败，或取数没取齐）");
        assertEquals(onShelf, docCount(), "ES 实测文档数必须等于在架数");

        // ⚠️ 这里**刻意不再**比较"重建前的全局计数 == 重建后"：
        //    本索引是**共享**的（单体还在用它检索，主 agent 的验证脚本也会打它），
        //    一次跨阶段的 `_count` 比较会被任何第三方写入/重建扰动 ⇒ 那是**结构性易红**，
        //    我实测就红过一次（05:23 那次：before=1374/after=1373，恰逢 8102 被重启、有并发活动）。
        //    换成**逐文档**断言：重建前存在的那篇文档，重建后仍在且字段完好。
        //    （这不比原来松：原来只说"总数没变"，现在说"这一篇的内容确实穿过重建活下来了"。）
        ProductSearchDoc after = getDoc(before.getSpuId());
        assertNotNull(after, "重建前存在的文档 " + before.getSpuId() + " 重建后必须还在");
        assertEquals(before.getTitle(), after.getTitle(), "重建不能改变文档内容");
        assertEquals(before.getStatus(), after.getStatus());
        assertEquals(before.getMinPrice(), after.getMinPrice());
    }

    @Test
    @DisplayName("[集成/reindex] 连续两次重建结果稳定（规格 §8 第 5 条；幂等）")
    void reindex_isIdempotent() throws Exception {
        long indexed1 = reindexAndGetIndexed();
        long indexed2 = reindexAndGetIndexed();
        assertEquals(indexed1, indexed2, "连续两次重建写入的文档数必须相同");
        assertEquals(indexed1, docCount(), "第二次重建后的实测文档数仍应等于写入数");
    }

    @Test
    @DisplayName("[集成/sync] 单条同步真调 product：在架商品 → 写成一个文档（不产生重复）；假 spuId → 不产生文档")
    void syncProduct_crossService() throws Exception {
        long realSpuId = requireRealSpuId();

        MvcResult ok = mockMvc.perform(internalPost("/internal/v1/search/sync/" + realSpuId, "{}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, codeOf(body(ok)));
        assertEquals(Boolean.TRUE, JsonPath.read(body(ok), "$.data"), "在架商品同步应当成功");
        assertTrue(docExists(realSpuId), "同步后文档必须在索引里");

        // ⚠️ 这里**刻意不从"全局计数不变"来断言**（共享索引上那是**结构性易红**：任何第三方写入都会扰动，
        //    我实测红过一次——05:23 那次 before=1373/after=1374，当时正有外部活动与 8102 重启）。
        //    换成**逐文档**的精确断言：这个 spuId 在索引里**恰好一篇**（既证明没重复建文档，也比数总数更锐利）。
        assertEquals(1L, docsWithSpuId(realSpuId), "同一个 spuId 在索引里必须恰好一篇文档（_id==spuId ⇒ 覆盖而非新增）");

        // 假 spuId：product 侧查不到 ⇒ 不产生文档（且不该误删/误建）
        long fakeSpuId = FAKE_SPU_ID_BASE + 5;
        MvcResult absent = mockMvc.perform(internalPost("/internal/v1/search/sync/" + fakeSpuId, "{}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, codeOf(body(absent)));
        assertEquals(Boolean.TRUE, JsonPath.read(body(absent), "$.data"), "不存在的商品也算'已按最新状态落索引'");
        assertFalse(docExists(fakeSpuId), "不存在的商品不该在索引里留下文档");
        assertEquals(0L, docsWithSpuId(fakeSpuId), "不存在的 spuId 文档数应为 0");
    }

    @Test
    @DisplayName("[集成/sync-by-brand] 真调 product 按品牌取内容：每个 spuId 恰好一篇（不用全局计数）")
    void syncByBrand_crossService() throws Exception {
        // ⚠️ 用一个**小**品牌（库里有 1 个在架商品）跑：逐个 doc 都 refresh(WaitFor)，大品牌会很慢
        //    （这是现状写入形态的固有代价，见 P6-2 汇报里关于"P6-5 可改 bulk+单次 refresh"的条目）
        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/sync-by-brand/2", "{}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b), "带令牌必须业务成功");
        int synced = ((Number) JsonPath.read(b, "$.data")).intValue();
        assertTrue(synced >= 1, "品牌 2 在 product 侧应有在架商品，实际 synced=" + synced);

        // 逐文档核验（**不用全局计数**：共享索引上那是结构性易红 —— 同 syncProduct_crossService 的注释）
        Long brandSpuId = anyIndexedSpuIdOfBrand(2L);
        assertNotNull(brandSpuId, "同步后索引里应当能查到 brandId=2 的文档");
        assertEquals(1L, docsWithSpuId(brandSpuId), "每个 spuId 恰好一篇文档（upsert 而非新增）");

        // 不存在的品牌：空结果而不是错误
        MvcResult none = mockMvc.perform(internalPost("/internal/v1/search/sync-by-brand/99999999", "{}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, codeOf(body(none)));
        assertEquals(0, ((Number) JsonPath.read(body(none), "$.data")).intValue());
    }

    /**
     * <b>索引内容 vs 库内容：逐字段比对</b>（主 agent 的判据"文档数相等 ≠ 索引不旧"的落点）。
     *
     * <p>为什么本服务里只有这一层能验：权威字段值（在架 SKU 最低价、总库存、销量、品牌名…）
     * 只有**商品域**知道，本服务没有库。所以这里通过 {@code /internal/v1/product/index-docs} 拿
     * 商品域给的权威文档，再与 ES 里实际存的文档**逐字段**比 —— 等价于主 agent 那个
     * {@code p6-index-content-audit.ps1} 的逐字段口径（他那条是直接对库查，我这条走契约）。
     *
     * <p>⚠️ 与"文档数相等"的区别：数相等时索引**完全可能整体是旧的**（某次同步失败留下的旧值）。
     * 逐字段比对才抓得到这种"数对、内容旧"的漂移。
     *
     * <p>product 不在时本套件整体 `assumeTrue` 跳过（跳过 = 这条没验，报告里单列）。
     */
    @Test
    @DisplayName("[集成/逐字段] 索引里的文档内容与 product 给的权威值**逐字段一致**（不只比文档数）")
    void indexContent_matchesProductFieldByField() {
        IndexDocsResult page = productIndexDocClient.page(1, 50);
        assertTrue(page.size() > 0, "product 必须给出在架商品的索引文档（前置条件）");

        int checked = 0;
        int mismatched = 0;
        StringBuilder bad = new StringBuilder();
        for (ProductSearchDoc expected : page.docs()) {
            ProductSearchDoc actual = getDoc(expected.getSpuId());
            if (actual == null) {
                mismatched++;
                bad.append("\n  spuId=").append(expected.getSpuId()).append(" 索引里没有这篇文档");
                continue;
            }
            checked++;
            // 逐字段（只比两边都应该一致的字段；spuId 作为 _id 单独断言）
            if (!java.util.Objects.equals(expected.getTitle(), actual.getTitle())) {
                mismatched++;
                bad.append("\n  spuId=").append(expected.getSpuId()).append(" title 索引=")
                        .append(actual.getTitle()).append(" 库=").append(expected.getTitle());
            }
            for (String field : new String[]{"status", "sales", "categoryId", "brandId", "brandName",
                    "minPrice", "totalStock", "createTimeMillis"}) {
                Object e = fieldOf(expected, field);
                Object a = fieldOf(actual, field);
                if (!java.util.Objects.equals(e, a)) {
                    mismatched++;
                    bad.append("\n  spuId=").append(expected.getSpuId()).append(' ').append(field)
                            .append(" 索引=").append(a).append(" 库=").append(e);
                }
            }
        }
        System.out.println("[逐字段比对] 抽查 " + checked + " 篇（product 报在架 " + page.totalInShelf()
                + " 篇），字段不一致 " + mismatched + " 处");
        assertEquals(0, mismatched,
                "索引内容与库内容必须逐字段一致（文档数相等证明不了这一点）：" + bad);
    }

    // ---------- helpers ----------

    /** 按字段名取值（保持断言在同一个循环里，避免 8 段重复代码） */
    private Object fieldOf(ProductSearchDoc doc, String field) {
        return switch (field) {
            case "status" -> doc.getStatus();
            case "sales" -> doc.getSales();
            case "categoryId" -> doc.getCategoryId();
            case "brandId" -> doc.getBrandId();
            case "brandName" -> doc.getBrandName();
            case "minPrice" -> doc.getMinPrice();
            case "totalStock" -> doc.getTotalStock();
            case "createTimeMillis" -> doc.getCreateTimeMillis();
            default -> throw new IllegalArgumentException("未覆盖的字段: " + field);
        };
    }

    // codeOf 已上移到 SearchTestSupport（共享支撑），这里不再各写一份

    private long reindexAndGetIndexed() throws Exception {
        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/reindex", "{}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b), "reindex 应当业务成功");
        return ((Number) JsonPath.read(b, "$.data.indexed")).longValue();
    }

    /**
     * 索引里 `spuId = X` 的文档数。
     *
     * <p>**为什么用它替代"全局文档数不变"**：本索引是共享的（单体在检索、主 agent 的验证脚本也会打它），
     * 一次跨阶段的 `_count` 比较会被任何第三方写入/重建扰动 —— 那是**结构性易红**（我实测红过一次）。
     * 而"这个 spuId 恰好一篇"是**逐文档**的、与其它写入者无关，并且比"总数没变"更锐利
     * （总数没变也可能是"删了一篇又加了一篇"这种抵消）。
     */
    private long docsWithSpuId(long spuId) throws Exception {
        return elasticsearchClient.count(c -> c.index(INDEX)
                .query(q -> q.term(t -> t.field("spuId").value(spuId)))).count();
    }

    /** 索引里属于某个品牌的任意一个 spuId（没有则 null） */
    private Long anyIndexedSpuIdOfBrand(long brandId) throws Exception {
        var response = elasticsearchClient.search(s -> s.index(INDEX).size(1)
                        .query(q -> q.term(t -> t.field("brandId").value(brandId))),
                com.mall.search.dto.ProductSearchDoc.class);
        return response.hits().hits().stream()
                .map(h -> h.source() == null ? null : h.source().getSpuId())
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    /** 从索引里取一篇真实文档（不写死 id，避免"某个商品被下架"导致假红） */
    private com.mall.search.dto.ProductSearchDoc anyIndexedDoc() throws Exception {
        var response = elasticsearchClient.search(s -> s.index(INDEX).size(1)
                        .sort(so -> so.field(f -> f.field("spuId").order(
                                co.elastic.clients.elasticsearch._types.SortOrder.Asc))),
                com.mall.search.dto.ProductSearchDoc.class);
        return response.hits().hits().stream()
                .map(h -> h.source())
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    /** 从索引里取一个真实 spuId（不写死，避免"某个商品被下架"导致假红）；取不到直接红 */
    private long requireRealSpuId() throws Exception {
        var doc = anyIndexedDoc();
        if (doc == null || doc.getSpuId() == null) {
            throw new AssertionError("索引里应当至少有一篇真实文档");
        }
        return doc.getSpuId();
    }
}
