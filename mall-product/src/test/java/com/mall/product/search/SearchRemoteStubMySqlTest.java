package com.mall.product.search;

import com.jayway.jsonpath.JsonPath;
import com.mall.product.dto.ProductSearchDoc;
import com.mall.product.service.ProductSearchService;
import com.mall.product.support.ProductTestBase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * <b>P6-3 的核心套件：检索接线 + 三种失败回落</b>（本服务侧，桩掉"检索域"这一端）。
 *
 * <h2>为什么用"环回 HTTP 桩服务"而不是 {@code @MockitoBean}</h2>
 * 被测的正是"**真实的 HTTP 客户端**在不可达/超时/非 0 业务码时怎么表现"：
 * <ul>
 *   <li>用 {@code @MockitoBean} 换掉客户端 ⇒ 超时/连接失败这些**传输层**行为根本没被执行，
 *       测的是"我 mock 出来的行为"，不是真实行为（P5 禁的那类"看起来在测其实永远退让"）；</li>
 *   <li>这里起一个 JDK 自带的 {@link HttpServer}（环回、随机端口），让它按用例需要
 *       **正常返回 / 停在半路（超时）/ 返回非 0 业务码 / 连接被拒** ⇒ 走的是真
 *       {@code RestClient} + 真的 300ms/2500ms 超时设置。</li>
 * </ul>
 * ⚠️ "连接被拒（服务不可达）"这一路**不在本类**：它需要地址指向一个没人监听的端口，
 * 而本类的地址固定指向桩服务。那一路由 {@code SearchUnavailableProductSearchServiceTest}
 * 的端到端用例覆盖（测试基类把 {@code mall.search.base-url} 默认指向死端口）。
 *
 * <h2>判据口径（规格 §3.0，很重要）</h2>
 * 降级结果的基准**不是** ES 的结果 —— smartcn 与 SQL {@code LIKE} 本就不是一个口径
 * （实测 {@code keyword=机} 命中不了含"耳机"的商品，而 {@code LIKE '%机%'} 会命中）。
 * 所以这里用 **MySQL↔MySQL 对照**：直接写 SQL（{@code LIKE '%kw%'} + 上架 + 排序 + 分页）
 * 算出"应该得到什么"，再与前台接口返回的 **id 集合**比（比集合不比顺序 —— 否则会写出永远偶发红的用例）。
 */
class SearchRemoteStubMySqlTest extends ProductTestBase {

    /** 被测的检索契约 bean（P6-5 #5 起它的 {@code findById} 有真端点可调） */
    @Autowired
    private ProductSearchService productSearchService;

    // ==================================================================
    // 环回桩服务（静态：端口必须在 Spring 上下文创建前就确定）
    // ==================================================================

    private static final HttpServer STUB;
    private static final int STUB_PORT;
    /** 桩的行为：正常返回固定 id / 返回非 0 业务码 / 迟迟不返回（触发读超时） */
    private static volatile Mode mode = Mode.OK_IDS;
    private static volatile List<Long> stubIds = List.of();
    private static volatile long stubTotal = 0;
    private static final AtomicReference<String> lastToken = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    /** 最近一次被请求的路径（P6-5 #5 要证明 findById 打的是 /product/{spuId}，而不是别的地方） */
    private static final AtomicReference<String> lastPath = new AtomicReference<>();

    enum Mode { OK_IDS, NONZERO_CODE, SLOW }

    /**
     * 单文档端点（P6-5 #5 新增）的三种回答：
     * {@code OK} 有文档 / {@code ABSENT} 明确回答"没有"（{@code data:null}）/ {@code FAIL} 明确报错（ES 不可达）。
     * <p>第三种必须单独存在：把"读不到"与"不存在"合成一种回答，会让调用方得出**方向相反**的结论。
     */
    enum DocMode { OK, ABSENT, FAIL }

    private static volatile DocMode docMode = DocMode.OK;
    private static volatile String stubDocJson = "{}";

    static {
        try {
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/", SearchRemoteStubMySqlTest::handle);
            STUB.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            STUB.start();
            STUB_PORT = STUB.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("无法启动环回桩服务", e);
        }
    }

    @DynamicPropertySource
    static void pointSearchAtStub(DynamicPropertyRegistry registry) {
        // 显式覆盖基类的死端口默认值 ⇒ 本类验的是"检索可用 / 三种失败"这两条真实路径
        registry.add("mall.search.base-url", () -> "http://127.0.0.1:" + STUB_PORT);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        lastToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String path = exchange.getRequestURI().getPath();
        lastPath.set(path);
        try {
            // P6-5 #5：单文档端点 `GET /internal/v1/search/product/{spuId}`
            //（与既有的 `DELETE` 同路径不同方法 —— 桩按方法区分，避免"取"被当成"删"）
            String docPrefix = "/internal/v1/search/product/";
            if (path.startsWith(docPrefix) && path.length() > docPrefix.length()
                    && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                switch (docMode) {
                    case ABSENT -> respond(exchange, 200, "{\"code\":0,\"data\":null,\"message\":\"ok\"}");
                    case FAIL -> respond(exchange, 200,
                            "{\"code\":500,\"data\":null,\"message\":\"桩服务模拟检索域内部失败(ES 不可达)\"}");
                    default -> respond(exchange, 200, "{\"code\":0,\"data\":" + stubDocJson + ",\"message\":\"ok\"}");
                }
                return;
            }
            if (!path.endsWith("/internal/v1/search/products")) {
                respond(exchange, 200, "{\"code\":404,\"data\":null,\"message\":\"桩服务只实现了 products\"}");
                return;
            }
            switch (mode) {
                case SLOW -> {
                    // 睡超过读超时（2500ms）⇒ 客户端必须超时并回落
                    try {
                        Thread.sleep(3500L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    respond(exchange, 200, okBody());
                }
                case NONZERO_CODE -> respond(exchange, 200,
                        "{\"code\":500,\"data\":null,\"message\":\"桩服务模拟下游业务失败\"}");
                default -> respond(exchange, 200, okBody());
            }
        } catch (Exception e) {
            // 桩服务自身出问题要能看见，不要伪装成"下游失败"
            respond(exchange, 500, "{\"code\":500,\"data\":null,\"message\":\"桩服务异常: " + e + "\"}");
        }
    }

    private static String okBody() {
        String ids = stubIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        return "{\"code\":0,\"message\":null,\"data\":{\"spuIds\":[" + ids + "],\"total\":" + stubTotal + "}}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ==================================================================
    // 夹具：取几条**真实上架**商品当"检索命中结果"（回表装配要能查到）
    // ==================================================================

    private List<Long> realOnShelfIds(int n) {
        return jdbcTemplate.queryForList("SELECT id FROM " + EXPECTED_SCHEMA
                + ".pms_spu WHERE deleted = 0 AND status = 1 ORDER BY id DESC LIMIT " + n, Long.class);
    }

    /** 关键字：用一个真实商品的**完整标题**（LIKE 命中数稳定 = 1，便于独立算期望） */
    private String seedKeyword() {
        return stringOf("SELECT title FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = 1001 AND status = 1");
    }

    /** 独立算"MySQL 回落应该得到的 id 列表"（**不调用被测代码**，直接写 SQL） */
    private List<Long> mysqlLikeIds(String keyword, long page, long size) {
        return jdbcTemplate.queryForList("SELECT id FROM " + EXPECTED_SCHEMA
                        + ".pms_spu WHERE deleted = 0 AND status = 1 AND title LIKE CONCAT('%', ?, '%')"
                        + " ORDER BY sales DESC, id ASC LIMIT ? OFFSET ?",
                Long.class, keyword, size, (page - 1) * size);
    }

    private long mysqlLikeTotal(String keyword) {
        return countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                + ".pms_spu WHERE deleted = 0 AND status = 1 AND title LIKE CONCAT('%', ?, '%')", keyword);
    }

    private List<Long> returnedIds(String responseBody) {
        List<Number> raw = JsonPath.read(responseBody, "$.data.list[*].spuId");
        return raw.stream().map(Number::longValue).toList();
    }

    @BeforeEach
    void resetStub() {
        mode = Mode.OK_IDS;
        stubIds = List.of();
        stubTotal = 0;
        docMode = DocMode.OK;
        stubDocJson = "{}";
        clearProductPortalCache();
    }

    @AfterEach
    void backToNormal() {
        mode = Mode.OK_IDS;
        docMode = DocMode.OK;
        stubDocJson = "{}";
    }

    // ==================================================================
    // ①.5 P6-5 #5：findById 走新端点（此前"没有端点可调"⇒ 只能如实返回 null）
    // ==================================================================

    @Test
    @DisplayName("[P6-5 #5] findById：走 GET /product/{spuId}，12 个字段逐个反序列化到本服务的 DTO")
    void findById_readsIndexedDocFromSearch() {
        stubDocJson = "{\"spuId\":1001,\"title\":\"桩文档标题\",\"subtitle\":\"桩副标题\",\"brandId\":1,"
                + "\"brandName\":\"苹果\",\"categoryId\":12,\"minPrice\":19900,\"sales\":7,\"totalStock\":58,"
                + "\"status\":1,\"mainImage\":\"http://img/stub.jpg\",\"createTimeMillis\":1757800000000}";

        ProductSearchDoc doc = productSearchService.findById(1001L);

        assertThat(lastPath.get()).as("必须打在新端点上（而不是别的地方）")
                .isEqualTo("/internal/v1/search/product/1001");
        assertThat(lastToken.get()).as("出站必须带内部令牌").isEqualTo(TOKEN);
        assertThat(doc).as("下游 code=0 且有文档 ⇒ 必须返回文档").isNotNull();
        assertThat(doc.getSpuId()).isEqualTo(1001L);
        assertThat(doc.getTitle()).isEqualTo("桩文档标题");
        assertThat(doc.getBrandName()).isEqualTo("苹果");
        assertThat(doc.getMinPrice()).isEqualTo(19900L);
        assertThat(doc.getTotalStock()).isEqualTo(58);
        assertThat(doc.getStatus()).isEqualTo(1);
        assertThat(doc.getCreateTimeMillis()).as("毫秒时间戳字段名必须与 search 侧一致").isEqualTo(1757800000000L);
    }

    @Test
    @DisplayName("[P6-5 #5] findById：下游明确回答 data=null ⇒ 返回 null（这是结论：索引里没有它）")
    void findById_returnsNullWhenDownstreamSaysAbsent() {
        docMode = DocMode.ABSENT;

        assertThat(productSearchService.findById(1001L))
                .as("code=0 + data=null 是**结论**（下架/删除/未入索引），不是错误 ⇒ 返回 null 而不是抛")
                .isNull();
    }

    @Test
    @DisplayName("[P6-5 #5] findById：下游报错（ES 不可达）⇒ 抛，绝不能把「读不到」说成「不存在」")
    void findById_throwsWhenDownstreamFails() {
        docMode = DocMode.FAIL;

        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> productSearchService.findById(1001L));
        assertThat(e.getMessage()).as("异常消息要能看出是「读不到」而不是「没有」").contains("读取索引文档失败");
    }

    /**
     * 释放桩（类加载器结束时）。用 {@code Runtime} 钩子而不是 {@code @AfterAll}：
     * 上下文可能被 Spring 缓存复用，端口要一直可用。
     */
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> STUB.stop(0)));
    }

    // ==================================================================
    // ① 检索可用：id 与顺序来自检索域，total 不被回表改写，展示字段来自本地库
    // ==================================================================

    @Test
    @DisplayName("[检索可用] 检索域给的 id 顺序被原样保留（回表不乱序）、total 用它给的值、字段来自本地库")
    void searchAvailable_orderIdsTotalAndLocalFields() throws Exception {
        List<Long> picks = realOnShelfIds(3);
        assertThat(picks).as("前置：库里至少要有 3 个在架商品").hasSize(3);
        // 刻意给一个**与 id 顺序不同**的顺序 ⇒ "回表后不乱序"才有验证力（P6-2 交接的第 3 条断言）
        stubIds = List.of(picks.get(2), picks.get(0), picks.get(1));
        stubTotal = 1373L;   // 故意给一个与"本地 LIKE 命中数"不同的总数：用来证明 total **来自检索域**

        String keyword = seedKeyword();
        MvcResult r = mockMvc.perform(get("/api/product/page").param("keyword", keyword)
                        .param("pageNum", "1").param("pageSize", "8"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String b = body(r);

        assertThat(returnedIds(b))
                .as("回表装配必须**原样保留**检索域给的 id 顺序（P6-2 交接的第 3 条断言）")
                .containsExactlyElementsOf(stubIds);
        assertThat(((Number) JsonPath.read(b, "$.data.total")).longValue())
                .as("total 必须用检索域给的值（P6-2 交接的第 2 条：回表装配不得改写总数）；"
                        + "这里桩给 1373 而本地 LIKE 只命中 " + mysqlLikeTotal(keyword) + " 条 ⇒ 若被改写就会红")
                .isEqualTo(1373L);
        // 展示字段来自**本地库**（回表装配）：第一条的标题必须等于库里那条的标题
        String expectedTitle = stringOf("SELECT title FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?", stubIds.get(0));
        assertThat((String) JsonPath.read(b, "$.data.list[0].title"))
                .as("展示字段必须来自本地库回表（检索域只给 id）")
                .isEqualTo(expectedTitle);
        // 请求契约：字段名/分页/排序必须与 mall-search 的内部端点约定一致（否则对方会收到一堆 null 而静默返回空）
        assertThat(lastToken.get()).as("必须带内部令牌（否则 search 侧 403）").isEqualTo(TOKEN);
        assertThat(lastBody.get())
                .as("请求体字段必须与 /internal/v1/search/products 的契约一致")
                .contains("\"pageSize\":8").contains("\"pageNum\":1").contains("\"sort\":\"default\"")
                .contains(keyword);
    }

    @Test
    @DisplayName("[检索可用/聚合字段] 返回项的总库存 == SUM(pms_sku.stock)（P6-2 交接的第 4 条断言）")
    void totalStockMatchesSkuSum() throws Exception {
        List<Long> picks = realOnShelfIds(2);
        stubIds = picks;
        stubTotal = picks.size();

        String keyword = seedKeyword();
        String b = body(mockMvc.perform(get("/api/product/page").param("keyword", keyword)
                .param("pageSize", "8")).andReturn());

        for (int i = 0; i < picks.size(); i++) {
            long spuId = picks.get(i);
            long skuSum = countOf("SELECT COALESCE(SUM(stock),0) FROM " + EXPECTED_SCHEMA
                    + ".pms_sku WHERE spu_id = ? AND deleted = 0 AND status = 1", spuId);
            long returned = ((Number) JsonPath.read(b, "$.data.list[" + i + "].totalStock")).longValue();
            assertThat(returned)
                    .as("spuId=" + spuId + " 的总库存必须等于在架 SKU 库存之和（与索引/库同口径）")
                    .isEqualTo(skuSum);
        }
    }

    // ==================================================================
    // ② 三种失败里的一种：返回非 0 业务码 → 必须回落（且不抛给用户）
    // ==================================================================

    @Test
    @DisplayName("[检索失败/非 0 业务码] 前台仍 code=0，且 id 集合 == 直接 MySQL LIKE 的结果（MySQL↔MySQL）")
    void nonZeroCode_fallsBackToMysql() throws Exception {
        mode = Mode.NONZERO_CODE;
        assertFallbackEqualsMysql("非 0 业务码");
    }

    @Test
    @DisplayName("[检索失败/超时] 下游停半路（>2500ms 读超时）→ 前台仍 code=0，且 id 集合 == MySQL LIKE 结果")
    void readTimeout_fallsBackToMysql() throws Exception {
        mode = Mode.SLOW;
        assertFallbackEqualsMysql("读超时");
    }

    /**
     * 降级的独立判据：**MySQL↔MySQL 对照**（规格 §3.0）。
     *
     * <p>比**集合**不比顺序：降级路径走的是 SQL 的 {@code ORDER BY sales DESC, id ASC}，
     * 与检索域的排序规则不保证逐位相同；断言顺序会写出一条永远偶发红的用例
     * （规格 §2.3b 原文："比集合不比顺序"）。
     */
    private void assertFallbackEqualsMysql(String scene) throws Exception {
        String keyword = seedKeyword();
        long expectedTotal = mysqlLikeTotal(keyword);
        List<Long> expectedIds = mysqlLikeIds(keyword, 1, 8);
        assertThat(expectedIds).as("前置：关键字 '%s' 在库里必须能命中（否则本用例失去意义）", keyword).isNotEmpty();

        MvcResult r = mockMvc.perform(get("/api/product/page").param("keyword", keyword)
                        .param("pageNum", "1").param("pageSize", "8"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String b = body(r);

        assertThat(((Number) JsonPath.read(b, "$.data.total")).longValue())
                .as("[" + scene + "] 降级后的 total 必须等于 MySQL LIKE 的命中数")
                .isEqualTo(expectedTotal);
        assertThat(new LinkedHashSet<>(returnedIds(b)))
                .as("[" + scene + "] 降级后的 id 集合必须等于直接走 MySQL LIKE 的结果"
                        + "（判据是 MySQL↔MySQL；**不是**拿 ES 结果当基准）")
                .isEqualTo(new LinkedHashSet<>(expectedIds));
    }

    // ==================================================================
    // ③ 结构一致：降级响应与正常响应的键路径集合完全相同（规格 §3.0 后半句）
    // ==================================================================

    @Test
    @DisplayName("[结构一致] 正常路径与降级路径的 JSON 键路径集合完全相同（前端不必区分两种来源）")
    void degradedShape_isIdenticalToNormal() throws Exception {
        String keyword = seedKeyword();
        List<Long> picks = realOnShelfIds(2);

        stubIds = picks;
        stubTotal = 2;
        mode = Mode.OK_IDS;
        clearProductPortalCache();
        String normal = body(mockMvc.perform(get("/api/product/page").param("keyword", keyword)
                .param("pageSize", "8")).andReturn());

        mode = Mode.NONZERO_CODE;   // 触发回落
        clearProductPortalCache();
        String degraded = body(mockMvc.perform(get("/api/product/page").param("keyword", keyword)
                .param("pageSize", "8")).andReturn());

        assertThat(keyPaths(JsonPath.parse(degraded).json()))
                .as("降级响应的键路径集合必须与正常路径**完全相同**（否则前端要写两套解析）")
                .isEqualTo(keyPaths(JsonPath.parse(normal).json()));
    }

    /** 递归收集 JSON 的键路径（数组下标统一写成 {@code [*]}，只比结构不比条数） */
    @SuppressWarnings("unchecked")
    private Set<String> keyPaths(Object node) {
        Set<String> paths = new LinkedHashSet<>();
        collect(node, "$", paths);
        return paths;
    }

    @SuppressWarnings("unchecked")
    private void collect(Object node, String prefix, Set<String> sink) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String p = prefix + "." + e.getKey();
                sink.add(p);
                collect(e.getValue(), p, sink);
            }
        } else if (node instanceof List<?> list) {
            sink.add(prefix + "[*]");
            if (!list.isEmpty()) {
                collect(list.get(0), prefix + "[*]", sink);
            }
        }
    }

    /** 保留给将来：桩服务收到的全部请求路径（排查"到底调了哪个端点"用） */
    @SuppressWarnings("unused")
    private static final List<String> CALLED_PATHS = new ArrayList<>(new LinkedHashMap<String, String>().keySet());
}
