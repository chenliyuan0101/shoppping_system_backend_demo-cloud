package com.mall.product.search;

import com.jayway.jsonpath.JsonPath;
import com.mall.product.client.SearchProductsClient;
import com.mall.product.mapper.SpuMapper;
import com.mall.product.support.ApiResponse;
import com.mall.product.support.ProductTestBase;
import com.mall.product.dto.ProductShelfQuery;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * <b>跨服务集成层（P6-3）</b>：真调 {@code mall-search}，接下 P6-2 交接的 §3.5 第 1/2/3 条断言。
 *
 * <h2>这一层为什么必须存在（而且要显式声明）</h2>
 * 上面那套桩服务验的是"本服务在检索可用/不可用时怎么处理"；**"检索域真的能回答"**只能打真服务。
 * 本类**显式退出**"死端口默认值"（基类把 {@code mall.search.base-url} 指向死端口），改成真地址；
 * 连不上时 {@code assume} **具名跳过** —— 跳过 = 这条没验，报告里单列。
 *
 * <h2>判据口径（规格 §3.0，别搞错）</h2>
 * <ul>
 *   <li><b>第 1 条（跨引擎总数）只在"无关键字 + 有筛选"的查询上比</b>：那种条件下
 *       检索域与 MySQL 的数的是**同一个集合**（在架 + 类目/品牌/价格），可以相等；
 *       而**带关键字时不能比** —— smartcn 词元匹配与 SQL {@code LIKE '%kw%'} 本就不是一个口径
 *       （实测 {@code keyword=机} 命中不了含"耳机"的商品）；</li>
 *   <li><b>第 2/3 条（total 不被改写、回表不乱序）</b>用"前台接口 vs 检索域内部接口"比：
 *       同一组参数下，前台返回的 id 序列必须**逐位等于**检索域给的 id 序列（P6-1 的回表装配
 *       只补展示字段、不改顺序、不改总数）。这条**与分词口径无关**，所以可以硬断言。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        // ⚠️ **刻意不覆盖 `mall.internal.token`**（这里踩过一次，记下来）：
        //    基类把它设成测试值 `test-internal-token`（为了验"无令牌 → 403"那类用例），
        //    但本类要**出站**调真 8103，带上测试令牌会被对方判 403 ⇒ 全部用例红成"检索域内部接口失败"。
        //    出站调用必须带**这个环境自己配的那把**（dev profile 的真令牌）——
        //    与 P6-2 那条结论同源："带正确令牌 == 带上环境自己配的那把"，没有测试专用钥匙。
        "mall.gateway.auth-token=test-gateway-token",
        // 本类**显式**指向真实检索域（默认直连 8103，让"打的是哪个实例"一目了然）
        "mall.search.base-url=${MALL_SEARCH_BASE_URL:http://127.0.0.1:8103}"
})
class SearchRemoteIntegrationTest extends ProductTestBase {

    @Autowired
    private SearchProductsClient searchProductsClient;

    @Autowired
    private SpuMapper spuMapper;

    @Value("${mall.search.base-url}")
    private String searchBaseUrl;

    @BeforeEach
    void requireLiveSearch() {
        boolean up = searchReachable();
        System.out.println("[检索集成层探活] base-url=" + searchBaseUrl + " reachable=" + up);
        Assumptions.assumeTrue(up,
                "mall-search 未启动，跳过检索集成用例（探活地址=" + searchBaseUrl + "）");
    }

    /** 用与客户端**同一个配置值**探活（不另取地址 —— 否则"探针说可达"与"客户端打哪儿"可能不一致） */
    private boolean searchReachable() {
        String base = searchBaseUrl == null || searchBaseUrl.isBlank() ? "http://127.0.0.1:8103" : searchBaseUrl;
        if (base.startsWith("lb://")) {
            return false;   // 服务发现形式：本层不做发现（由调用方给直连地址）
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/actuator/health"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================================================================
    // §3.5 第 1 条：跨引擎总数（只在"无关键字 + 有筛选"时可比）
    // ==================================================================

    @Test
    @DisplayName("[集成/第1条] 无关键字 + 类目筛选：检索域的 total == MySQL countShelf（跨引擎总数一致）")
    void filterOnly_totalMatchesMysql() {
        // 取一个真实存在、且在架商品数 > 0 的类目（不写死，避免"类目被删/清空"导致假红）
        Long categoryId = jdbcTemplate.queryForObject("SELECT category_id FROM " + EXPECTED_SCHEMA
                + ".pms_spu WHERE deleted = 0 AND status = 1 GROUP BY category_id"
                + " ORDER BY COUNT(*) DESC LIMIT 1", Long.class);
        assertThat(categoryId).as("前置：库里应当有上架商品").isNotNull();

        ProductShelfQuery query = new ProductShelfQuery();
        query.setKeyword(null);                       // ⚠️ 无关键字 —— 有关键字时两边口径不同，不能比
        query.setCategoryIds(List.of(categoryId));
        query.setSort("default");

        long mysqlTotal = spuMapper.countShelf(query);

        ApiResponse<com.mall.product.dto.ProductIdPage> response = searchProductsClient.searchProducts(
                null, List.of(categoryId), null, null, null, "default", 1, 1);
        assertThat(response.getCode()).as("检索域内部接口必须业务成功").isZero();
        assertThat(response.getData()).isNotNull();

        assertThat(response.getData().total())
                .as("检索域的在架总数必须等于 MySQL countShelf（P6-2 交接的第 1 条断言；"
                        + "这是**跨引擎**对照，只在无关键字时成立）")
                .isEqualTo(mysqlTotal);
    }

    // ==================================================================
    // §3.5 第 2/3 条：前台 total 不被改写、回表后不乱序
    // ==================================================================

    @Test
    @DisplayName("[集成/第2·3条] 同一组参数：前台返回的 id 序列**逐位等于**检索域的 id 序列（回表不乱序）")
    void portalPreservesSearchOrderAndTotal() throws Exception {
        String keyword = seedKeyword();
        int pageSize = 8;

        ApiResponse<com.mall.product.dto.ProductIdPage> direct = searchProductsClient.searchProducts(
                keyword, List.of(), null, null, null, "default", 1, pageSize);
        assertThat(direct.getCode()).isZero();
        assertThat(direct.getData().spuIds())
                .as("前置：关键字 '%s' 在检索域里应当能命中（否则本用例失去意义）", keyword).isNotEmpty();

        clearProductPortalCache();   // 货架分页有缓存
        MvcResult r = mockMvc.perform(get("/api/product/page").param("keyword", keyword)
                        .param("pageNum", "1").param("pageSize", String.valueOf(pageSize)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String b = body(r);

        List<Long> portalIds = ((List<Number>) JsonPath.read(b, "$.data.list[*].spuId")).stream()
                .map(Number::longValue).toList();
        assertThat(portalIds)
                .as("回表装配必须原样保留检索域给的 **id 顺序**（P6-2 交接的第 3 条）")
                .containsExactlyElementsOf(direct.getData().spuIds());
        assertThat(((Number) JsonPath.read(b, "$.data.total")).longValue())
                .as("前台的 total 必须等于检索域的 total（P6-2 交接的第 2 条：回表不得改写总数）")
                .isEqualTo(direct.getData().total());

        // 展示字段确实来自本地库回表（检索域只给 id）
        String expectedTitle = stringOf("SELECT title FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?", portalIds.get(0));
        assertThat((String) JsonPath.read(b, "$.data.list[0].title")).isEqualTo(expectedTitle);
    }

    // ==================================================================
    // 检索可用时前台的关键字路径（与"死端口默认值"那条端到端用例互为反向证据）
    // ==================================================================

    @Test
    @DisplayName("[集成/可用] 前台关键字检索走真检索域：code=0 且有结果（与降级路径互为反向证据）")
    void keywordSearch_usesLiveSearch() throws Exception {
        String keyword = seedKeyword();
        clearProductPortalCache();

        MvcResult r = mockMvc.perform(get("/api/product/page").param("keyword", keyword))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String b = body(r);

        assertThat(((Number) JsonPath.read(b, "$.data.total")).longValue())
                .as("关键字 '%s' 在检索域里能命中 ⇒ 前台必须有结果", keyword).isGreaterThanOrEqualTo(1);
        assertThat(((List<?>) JsonPath.read(b, "$.data.list"))).isNotEmpty();
    }

    private String seedKeyword() {
        // ⚠️ 关键字为什么**不**直接取 seed 商品的完整标题（这里踩过一次，记下来）：
        //    完整标题（如"示例商品·无线降噪耳机 Pro"）在 MySQL 回落路径上必然命中
        //    （`LIKE '%整串%'` 是子串匹配），但在**检索域**上走的是 match_phrase =
        //    "分词后的词元必须按序相邻" ⇒ 整串短语**可能一条都不命中**（间隔符/空格切词后不连续）。
        //    这正是规格 §3.0 那条口径差异的另一个面：**别拿 LIKE 的直觉去写 ES 的断言**。
        //    改用 seed 标题里的一个**真实词元**："耳机"（smartcn 实测切成 [蓝, 牙, 耳机, 降, 噪]，
        //    "耳机"是词典词 ⇒ 单独查它是一个词元，必然与含该词元的文档命中）。
        String title = stringOf("SELECT title FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = 1001 AND status = 1");
        assertThat(title).as("前置：seed SPU 1001 必须存在且上架").isNotBlank();
        return "耳机";
    }
}
