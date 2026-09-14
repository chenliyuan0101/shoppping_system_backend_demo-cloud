package com.mall.admin.controller;

import com.jayway.jsonpath.JsonPath;
import com.mall.admin.support.BffStubTestBase;
import com.mall.admin.support.DownstreamStubs;
import com.mall.admin.support.DownstreamTestWiring;
import com.mall.admin.support.JsonShape;
import com.mall.admin.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>P7 §3 降级矩阵的公共部分</b>：把**某一个**依赖的地址指向死端口（127.0.0.1:9），
 * 断言看板三个端点仍然 "HTTP 200 + code=0 + 键路径集合不变 + 缺项 0/空"。
 *
 * <h2>为什么是"每个依赖一个子类"，而不是一个类里循环三个地址</h2>
 * 出站客户端的 {@code base-url} 是**构造期**读取的（{@code RestClient.baseUrl(...)}），
 * 因此"把某个依赖指到死端口"必须在 **Spring 上下文启动之前**决定 —— 也就是
 * {@code @DynamicPropertySource}（每个类一份），不能在用例方法里改。
 * 三个子类共享这里的断言，只有"哪一个是死的"不同；这样矩阵的每一格都是**真跑出来**的，
 * 而不是"改一个 flag 再断言"。
 *
 * <h2>死端口为什么能代表"服务挂了"</h2>
 * 127.0.0.1:9 是保留端口，本机没有任何进程监听 ⇒ 连接被拒（{@code ConnectException}），
 * 与"服务没起来/进程挂了"在客户端侧是同一种表现（{@code ResourceAccessException}）。
 * 这也是 mall-product 测试里 {@code ${MALL_*_BASE_URL:http://127.0.0.1:9}} 的同一手法：
 * **不需要启停任何进程**（本项目的硬纪律）。另外两种不可用（非 0 业务码 / 读超时）
 * 由 {@link AdminDashboardStubTest} 用环回桩覆盖。
 *
 * <h2>断言里的键路径是**字面量集合**，不是"再取一次健康响应来比"</h2>
 * 本上下文里被指向死端口的那个依赖永远不可用，取不到它的健康响应。所以这里直接断言
 * **契约的字面键路径集合**（例如 summary 恒为 {@code $.code/message/data + data 的 6 个字段}），
 * 而"健康响应确实产生这个集合"由 {@link AdminDashboardStubTest} 用真桩钉住
 * （那边有健康 vs 降级的完整比对）。两处合起来才构成完整证据链：
 * 健康 = 契约集合（桩套件证明） ＋ 降级 = 同一个契约集合（本套件证明）。
 */
abstract class AbstractDashboardDeadDependencyTest extends BffStubTestBase {

    /** 被判为"服务不可用"的域名：{@code trade} / {@code product} / {@code user} */
    protected abstract String deadDomain();

    protected final boolean tradeDead() {
        return DownstreamStubs.TRADE.name.equals(deadDomain());
    }

    protected final boolean productDead() {
        return DownstreamStubs.PRODUCT.name.equals(deadDomain());
    }

    protected final boolean userDead() {
        return DownstreamStubs.USER.name.equals(deadDomain());
    }

    private LogCapture logs;

    @BeforeEach
    void startLogCapture() {
        logs = LogCapture.on("com.mall.admin");
    }

    @AfterEach
    void stopLogCapture() {
        if (logs != null) {
            logs.close();
        }
    }

    // ==================================================================
    // summary：标量端点 —— 键路径集合必须**逐字**不变
    // ==================================================================

    @Test
    @DisplayName("[降级矩阵] summary：HTTP 200 / code=0 / 键路径集合不变；挂掉的那一域为 0，其余域仍是真值")
    void summary_degradesOnlyTheUnavailableDomain() throws Exception {
        logs.clear();
        String json = fetch("/api/admin/dashboard/summary");

        System.out.println("[降级矩阵] dead=" + deadDomain() + " endpoint=summary http=200 code="
                + readInt(json, "$.code") + " keys=" + JsonShape.keyPaths(json)
                + " data=" + JsonPath.parse(json).read("$.data", java.util.Map.class));

        assertThat(readInt(json, "$.code")).as("降级不是报错：code 必须恒为 0").isZero();
        assertThat(readString(json, "$.message")).isEqualTo("ok");
        assertThat(JsonShape.keyPaths(json))
                .as("键路径集合必须与健康时**完全相同**（9 条：信封 3 + summary 的 6 个字段）")
                .containsExactlyInAnyOrder(
                        "$.code", "$.message", "$.data",
                        "$.data.todayOrderCount", "$.data.todaySalesAmount",
                        "$.data.waitShipCount", "$.data.refundPendingCount",
                        "$.data.onShelfProductCount", "$.data.memberCount");
        assertThat(JsonPath.parse(json).read("$.data", Object.class))
                .as("data 永远不是 null（降级值是 0，不是 null）").isNotNull();

        // 按域断言：**只有**挂掉的那一域是 0，其余两域仍取到真值（这正是并行 + 分域降级的意义）
        long trade1 = readLong(json, "$.data.todayOrderCount");
        long trade2 = readLong(json, "$.data.todaySalesAmount");
        long trade3 = readLong(json, "$.data.waitShipCount");
        long trade4 = readLong(json, "$.data.refundPendingCount");
        long product = readLong(json, "$.data.onShelfProductCount");
        long user = readLong(json, "$.data.memberCount");

        if (tradeDead()) {
            assertThat(new long[]{trade1, trade2, trade3, trade4}).containsOnly(0L);
            assertThat(product).isEqualTo(DownstreamStubs.PRODUCT_ENABLED_COUNT);
            assertThat(user).isEqualTo(DownstreamStubs.USER_MEMBER_COUNT);
        } else if (productDead()) {
            assertThat(product).isZero();
            assertThat(trade1).isEqualTo(DownstreamStubs.TRADE_TODAY_ORDER_COUNT);
            assertThat(trade2).isEqualTo(DownstreamStubs.TRADE_TODAY_SALES_AMOUNT);
            assertThat(trade3).isEqualTo(DownstreamStubs.TRADE_WAIT_SHIP_COUNT);
            assertThat(trade4).isEqualTo(DownstreamStubs.TRADE_REFUND_PENDING_COUNT);
            assertThat(user).isEqualTo(DownstreamStubs.USER_MEMBER_COUNT);
        } else {
            assertThat(user).isZero();
            assertThat(trade1).isEqualTo(DownstreamStubs.TRADE_TODAY_ORDER_COUNT);
            assertThat(product).isEqualTo(DownstreamStubs.PRODUCT_ENABLED_COUNT);
        }

        // 每请求恰好一条 WARN，且点名的域就是挂掉的那个（三个域都是 summary 的依赖）
        assertThat(logs.warnMessages()).as("每请求恰好一条 WARN").hasSize(1);
        assertThat(logs.warnMessages().get(0))
                .as("日志必须点名是哪个域不可用").contains("endpoint=summary").contains(deadDomain());
    }

    // ==================================================================
    // trend / top：列表端点 —— 缺项为空数组，信封键路径不变
    // ==================================================================

    @Test
    @DisplayName("[降级矩阵] trend：交易域挂 ⇒ 空数组且信封键路径不变；否则元素契约成立")
    void trend_matrix() throws Exception {
        logs.clear();
        String json = fetch("/api/admin/dashboard/trend?days=4");

        System.out.println("[降级矩阵] dead=" + deadDomain() + " endpoint=trend http=200 code="
                + readInt(json, "$.code") + " keys=" + JsonShape.keyPaths(json)
                + " size=" + readList(json, "$.data").size() + " warns=" + logs.warnMessages().size());

        assertThat(readInt(json, "$.code")).isZero();
        if (tradeDead()) {
            assertThat(readList(json, "$.data")).as("交易域是趋势的唯一来源 ⇒ 缺项是**空数组**（不是 null）").isEmpty();
            assertThat(JsonShape.keyPaths(json))
                    .as("信封与 data[*] 的键路径必须与健康时一致（元素键在空数组里必然不存在，见报告 keysDelta）")
                    .containsExactlyInAnyOrder("$.code", "$.message", "$.data", "$.data[*]");
            assertThat(logs.warnMessages()).hasSize(1);
            assertThat(logs.warnMessages().get(0)).contains("endpoint=trend").contains("trade");
        } else {
            assertThat(readList(json, "$.data")).hasSize(4);
            assertThat(JsonShape.elementPaths(JsonShape.keyPaths(json)))
                    .containsExactlyInAnyOrder("date", "orderCount", "salesAmount");
            assertThat(logs.warns()).as("交易域是好的 ⇒ 本请求不该有降级 WARN").isEmpty();
        }
    }

    @Test
    @DisplayName("[降级矩阵] top?type=sales：商品域挂 ⇒ 空数组；否则元素契约成立")
    void topSales_matrix() throws Exception {
        logs.clear();
        String json = fetch("/api/admin/dashboard/top?type=sales&limit=3");

        System.out.println("[降级矩阵] dead=" + deadDomain() + " endpoint=top?type=sales http=200 code="
                + readInt(json, "$.code") + " keys=" + JsonShape.keyPaths(json)
                + " size=" + readList(json, "$.data").size() + " warns=" + logs.warnMessages().size());

        assertThat(readInt(json, "$.code")).isZero();
        if (productDead()) {
            assertThat(readList(json, "$.data")).as("商品域是销量榜的行来源 ⇒ 空数组").isEmpty();
            assertThat(JsonShape.keyPaths(json))
                    .containsExactlyInAnyOrder("$.code", "$.message", "$.data", "$.data[*]");
            assertThat(logs.warnMessages()).hasSize(1);
            assertThat(logs.warnMessages().get(0)).contains("endpoint=top").contains("product");
        } else {
            assertThat(readList(json, "$.data")).isNotEmpty();
            assertThat(JsonShape.elementPaths(JsonShape.keyPaths(json)))
                    .containsExactlyInAnyOrder("spuId", "title", "mainImage", "value");
            assertThat(logs.warns()).isEmpty();
        }
    }

    @Test
    @DisplayName("[降级矩阵] top?type=amount：行来源（交易）或补数（商品）任一挂 ⇒ 空数组；否则元素契约成立")
    void topAmount_matrix() throws Exception {
        logs.clear();
        String json = fetch("/api/admin/dashboard/top?type=amount&limit=3");

        System.out.println("[降级矩阵] dead=" + deadDomain() + " endpoint=top?type=amount http=200 code="
                + readInt(json, "$.code") + " keys=" + JsonShape.keyPaths(json)
                + " size=" + readList(json, "$.data").size() + " warns=" + logs.warnMessages().size());

        assertThat(readInt(json, "$.code")).isZero();
        boolean expectedEmpty = tradeDead() || productDead();
        if (expectedEmpty) {
            assertThat(readList(json, "$.data"))
                    .as("销售额榜需要「交易域给金额 + 商品域给标题」两个域都活着 ⇒ 任一挂掉都是空数组"
                            + "（**绝不**把服务不可用显示成「商品已删除」）")
                    .isEmpty();
            assertThat(JsonShape.keyPaths(json))
                    .containsExactlyInAnyOrder("$.code", "$.message", "$.data", "$.data[*]");
            assertThat(logs.warnMessages()).hasSize(1);
            assertThat(logs.warnMessages().get(0))
                    .contains("endpoint=top")
                    .contains(tradeDead() ? "trade" : "product");
        } else {
            assertThat(readList(json, "$.data")).isNotEmpty();
            assertThat(JsonShape.elementPaths(JsonShape.keyPaths(json)))
                    .containsExactlyInAnyOrder("spuId", "title", "mainImage", "value");
            assertThat(readLong(json, "$.data[0].value")).isEqualTo(DownstreamStubs.TOP_SPU1_AMOUNT);
            assertThat(logs.warns()).as("两个依赖都活着（用户域与 top 无关）⇒ 无降级 WARN").isEmpty();
        }
    }

    @Test
    @DisplayName("[降级矩阵] 不变量：所有端点 HTTP 恒 200、code 恒 0、data 永不为 null（任何依赖不可用时）")
    void everyEndpointStaysHttpOkCodeZero() throws Exception {
        String[] urls = {
                "/api/admin/dashboard/summary",
                "/api/admin/dashboard/trend?days=7",
                "/api/admin/dashboard/top?type=sales&limit=10",
                "/api/admin/dashboard/top?type=amount&limit=10",
        };
        for (String url : urls) {
            // 每个 URL 只取一次（降级结果不入缓存；健康结果按参数区分，不会被别的用例掩盖）
            String json = fetch(url);
            assertThat(readInt(json, "$.code")).as("url=%s 必须 code=0", url).isZero();
            assertThat(JsonPath.parse(json).read("$.data", Object.class))
                    .as("url=%s 的 data 绝不能是 null（缺项是 0 或空数组）", url).isNotNull();
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private String fetch(String url) throws Exception {
        MvcResult result = mockMvc.perform(gw(get(url)))
                .andExpect(status().isOk())   // 🔴 矩阵的第一条判据：HTTP 恒 200
                .andReturn();
        return body(result);
    }

    /** 三个子类共用：把某两个域接到桩、一个域接到死端口 */
    protected static void wireWithDead(String deadDomain, DynamicPropertyRegistry registry) {
        String trade = DownstreamStubs.TRADE.name.equals(deadDomain)
                ? DownstreamTestWiring.DEAD_URL : DownstreamStubs.TRADE.url();
        String product = DownstreamStubs.PRODUCT.name.equals(deadDomain)
                ? DownstreamTestWiring.DEAD_URL : DownstreamStubs.PRODUCT.url();
        String user = DownstreamStubs.USER.name.equals(deadDomain)
                ? DownstreamTestWiring.DEAD_URL : DownstreamStubs.USER.url();
        DownstreamTestWiring.wire(registry, trade, product, user);
    }

    private static List<Object> readList(String json, String path) {
        return new ArrayList<>(JsonPath.read(json, path));
    }

    private static int readInt(String json, String path) {
        return ((Number) JsonPath.read(json, path)).intValue();
    }

    private static long readLong(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private static String readString(String json, String path) {
        return JsonPath.read(json, path);
    }
}
