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
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>P7 §3 的核心套件：看板的并行聚合 / 短 TTL 缓存 / 降级不变形</b>（三个下游全部用环回桩）。
 *
 * <h2>四组证据（每组都是可执行的，不是"看起来"）</h2>
 * <ol>
 *   <li><b>并行</b>：三个桩各自"收到请求先 +1 在途计数、应答前 -1"，峰值并发 == 3
 *       ＋ 墙钟时间 ≈ 最慢的一个（400ms）而不是三者之和（1200ms）。</li>
 *   <li><b>缓存</b>：第一次请求三个域各被打 1 次；第二次**一次都没打**（命中缓存，且返回值逐字相同）；
 *       TTL 落在分钟级区间；换代后立刻重新打下游。</li>
 *   <li><b>降级矩阵</b>：非 0 业务码 / 读超时两种"不可用"各测一遍——HTTP 200、code=0、
 *       被降级的域为 0 而**其余域仍是真的**、每请求恰好一条 {@code log.warn}。</li>
 *   <li><b>结构不变</b>：正常 vs 降级的键路径集合比对（信封与标量键必须完全相同；
 *       列表被清空时"元素键"必然消失——这一点在用例里显式写出来，而不是靠断言含糊过去）。</li>
 * </ol>
 *
 * <p>第四种"服务不在"（连接被拒）由三个 {@code AdminDashboard*DownTest} 用死端口覆盖
 * （环回桩的地址固定，没法在本类里模拟"没人监听"）。
 */
class AdminDashboardStubTest extends BffStubTestBase {

    static final String SUMMARY = "/api/admin/dashboard/summary";
    static final String TREND = "/api/admin/dashboard/trend";
    static final String TOP = "/api/admin/dashboard/top";

    /** summary 的 data 键集合（C1 契约：与单体 DashboardVO.Summary 的 6 个字段逐字一致） */
    static final Set<String> SUMMARY_KEYS = Set.of("todayOrderCount", "todaySalesAmount", "waitShipCount",
            "refundPendingCount", "onShelfProductCount", "memberCount");
    /** TrendItem 的元素契约 */
    static final Set<String> TREND_ITEM_KEYS = Set.of("date", "orderCount", "salesAmount");
    /** TopItem 的元素契约 */
    static final Set<String> TOP_ITEM_KEYS = Set.of("spuId", "title", "mainImage", "value");

    private LogCapture logs;

    @DynamicPropertySource
    static void pointAtStubs(DynamicPropertyRegistry registry) {
        DownstreamTestWiring.wireAllToStubs(registry);
    }

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
    // ① 并行聚合
    // ==================================================================

    @Test
    @DisplayName("[§3 并行] summary 的三个下游请求**重叠**（峰值并发 3）且总耗时≈最慢的一个，不是三者之和")
    void summary_callsThreeDomainsInParallel() throws Exception {
        // 三个域各睡 400ms：串行 ⇒ ≥1200ms；并行 ⇒ ≈400ms
        DownstreamStubs.TRADE.slow(400L);
        DownstreamStubs.PRODUCT.slow(400L);
        DownstreamStubs.USER.slow(400L);
        DownstreamStubs.resetConcurrency();

        long start = System.nanoTime();
        String json = fetch(SUMMARY);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        System.out.println("[§3 并行] summary：三个下游各 sleep 400ms ⇒ 峰值并发=" + DownstreamStubs.peakInFlight()
                + " · 墙钟=" + elapsedMs + "ms（若串行则 ≥1200ms；实测最慢的一个=400ms）");

        assertThat(readInt(json, "$.code")).isZero();
        assertThat(DownstreamStubs.peakInFlight())
                .as("三个下游必须**同时在途**（服务端看到的峰值并发）：峰值 1 说明是串行，2 说明只并了两路。"
                        + "实测峰值=%d，耗时=%dms", DownstreamStubs.peakInFlight(), elapsedMs)
                .isEqualTo(3);
        assertThat(elapsedMs)
                .as("墙钟耗时必须接近最慢的一个（400ms）而不是三者之和（1200ms）：实测 %dms", elapsedMs)
                .isLessThan(1100L);
        assertThat(elapsedMs)
                .as("桩确实睡了（否则上面的时间断言是空的）：实测 %dms", elapsedMs)
                .isGreaterThanOrEqualTo(350L);

        // 三个域各被打了一次（并行不等于重复调用）
        assertThat(DownstreamStubs.TRADE.calls()).isEqualTo(1);
        assertThat(DownstreamStubs.PRODUCT.calls()).isEqualTo(1);
        assertThat(DownstreamStubs.USER.calls()).isEqualTo(1);
        // 出站必须带内部令牌（否则真实下游会 403，看板会静默降级成全 0）
        assertThat(DownstreamStubs.TRADE.tokens()).containsExactly(DownstreamTestWiring.INTERNAL_TOKEN);
    }

    @Test
    @DisplayName("[C1] summary 形状：data 恰好 6 个字段，值分别来自三个域")
    void summary_shapeAndValues() throws Exception {
        String json = fetch(SUMMARY);

        assertThat(readInt(json, "$.code")).isZero();
        assertThat(readString(json, "$.message")).isEqualTo("ok");
        assertThat(keysOf(json, "$.data")).as("键集合必须与单体 DashboardVO.Summary 逐字一致")
                .containsExactlyInAnyOrderElementsOf(SUMMARY_KEYS);
        assertThat(readLong(json, "$.data.todayOrderCount")).isEqualTo(DownstreamStubs.TRADE_TODAY_ORDER_COUNT);
        assertThat(readLong(json, "$.data.todaySalesAmount")).isEqualTo(DownstreamStubs.TRADE_TODAY_SALES_AMOUNT);
        assertThat(readLong(json, "$.data.waitShipCount")).isEqualTo(DownstreamStubs.TRADE_WAIT_SHIP_COUNT);
        assertThat(readLong(json, "$.data.refundPendingCount")).isEqualTo(DownstreamStubs.TRADE_REFUND_PENDING_COUNT);
        assertThat(readLong(json, "$.data.onShelfProductCount")).isEqualTo(DownstreamStubs.PRODUCT_ENABLED_COUNT);
        assertThat(readLong(json, "$.data.memberCount")).isEqualTo(DownstreamStubs.USER_MEMBER_COUNT);
    }

    @Test
    @DisplayName("[C1] trend/top 形状：元素键集合与单体契约一致（trend 3 个 / top 4 个）")
    void trendAndTop_shape() throws Exception {
        String trend = fetch(TREND + "?days=3");
        assertThat(readInt(trend, "$.code")).isZero();
        assertThat(readList(trend, "$.data")).as("days=3 ⇒ 3 个点（证明 days 被原样透传）").hasSize(3);
        assertThat(JsonShape.elementPaths(JsonShape.keyPaths(trend)))
                .as("TrendItem 的元素键（C1 契约）").containsExactlyInAnyOrderElementsOf(TREND_ITEM_KEYS);
        assertThat(readString(trend, "$.data[0].date")).isEqualTo("2026-09-01");
        assertThat(readLong(trend, "$.data[2].salesAmount")).isEqualTo(300L);
        assertThat(DownstreamStubs.TRADE.paths().get(0)).as("days 必须原样转发给交易域").contains("days=3");

        String top = fetch(TOP + "?type=sales&limit=5");
        assertThat(readInt(top, "$.code")).isZero();
        assertThat(JsonShape.elementPaths(JsonShape.keyPaths(top)))
                .as("TopItem 的元素键（C1 契约）").containsExactlyInAnyOrderElementsOf(TOP_ITEM_KEYS);
        assertThat(readLong(top, "$.data[0].spuId")).isEqualTo(DownstreamStubs.TOP_SPU_ID);
        assertThat(readString(top, "$.data[0].title")).isEqualTo("桩商品A");
        assertThat(readLong(top, "$.data[0].value")).as("sales 榜的 value = 销量").isEqualTo(DownstreamStubs.TOP_SPU1_SALES);
        assertThat(DownstreamStubs.PRODUCT.paths().get(0)).as("limit 必须原样转发给商品域").contains("limit=5");
    }

    // ==================================================================
    // ② 缓存：命中 / TTL / 换代失效
    // ==================================================================

    @Test
    @DisplayName("[§3 缓存] 第二次请求命中缓存：**下游一次都没被打**，且返回值逐字相同；TTL 是分钟级")
    void summary_cacheHitWithinTtl() throws Exception {
        String first = fetch(SUMMARY);
        assertThat(DownstreamStubs.TRADE.calls()).isEqualTo(1);

        String second = fetch(SUMMARY);

        assertThat(DownstreamStubs.TRADE.calls()).as("第二次必须命中缓存（不再打交易域）").isEqualTo(1);
        assertThat(DownstreamStubs.PRODUCT.calls()).isEqualTo(1);
        assertThat(DownstreamStubs.USER.calls()).isEqualTo(1);
        assertThat(second).as("缓存命中的响应必须与首次逐字相同（同一个 VO 序列化）").isEqualTo(first);

        String key = dashboardCache.key("summary");
        Long ttl = ttlSeconds(key);
        assertThat(ttl).as("TTL 必须是分钟级（配置 60s，CacheService 会加 ±15%% 抖动）：key=%s ttl=%s", key, ttl)
                .isNotNull()
                .isBetween(45L, 90L);
    }

    @Test
    @DisplayName("[§3 主动失效] 换代后下一次请求立刻重新打三个下游（旧代的键不再可达）")
    void cacheEvictionMakesNextRequestFresh() throws Exception {
        fetch(SUMMARY);
        fetch(SUMMARY);
        assertThat(DownstreamStubs.TRADE.calls()).as("前置：第二次已是缓存命中").isEqualTo(1);

        long newGeneration = dashboardCache.evictAll();
        assertThat(newGeneration).as("换代必须真的把计数器 +1（Redis 可用）").isGreaterThan(0L);

        fetch(SUMMARY);
        assertThat(DownstreamStubs.TRADE.calls()).as("换代后必须重新取数").isEqualTo(2);
        assertThat(DownstreamStubs.PRODUCT.calls()).isEqualTo(2);
        assertThat(DownstreamStubs.USER.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("[§3 缓存] 不同参数是不同条目：trend?days=3 与 days=5 不互相污染")
    void cacheKeysArePerParameter() throws Exception {
        fetch(TREND + "?days=3");
        fetch(TREND + "?days=3");
        assertThat(DownstreamStubs.TRADE.calls()).as("days=3 第二次命中缓存").isEqualTo(1);

        String five = fetch(TREND + "?days=5");
        assertThat(DownstreamStubs.TRADE.calls()).as("days=5 是另一个条目 ⇒ 必须重新取数").isEqualTo(2);
        assertThat(readList(five, "$.data")).hasSize(5);
    }

    // ==================================================================
    // ③④ 降级：非 0 业务码 / 读超时（+ 结构不变 + 单条 warn + 不入缓存 + 恢复）
    // ==================================================================

    @Test
    @DisplayName("[§3 降级/非 0 业务码] 交易域报错 ⇒ 仍 200/code=0、四个交易字段为 0、其余域仍是真的、恰好 1 条 warn")
    void summary_degradedOnNonZeroCode_keysIdentical() throws Exception {
        String healthy = fetch(SUMMARY);
        Set<String> healthyPaths = JsonShape.keyPaths(healthy);

        // ⚠️ 必须换代：否则这次请求会命中上一步写下的**正常**缓存，测到的就不是降级路径了
        dashboardCache.evictAll();
        DownstreamStubs.TRADE.mode(DownstreamStubs.Mode.NONZERO_CODE);
        logs.clear();
        String degraded = fetch(SUMMARY);

        assertThat(readInt(degraded, "$.code")).as("HTTP 200 + code=0（降级不是报错）").isZero();
        assertThat(readString(degraded, "$.message")).isEqualTo("ok");
        assertThat(keysOf(degraded, "$.data")).as("**键路径集合与正常时完全相同**（标量端点可以逐字比）")
                .isEqualTo(keysOf(healthy, "$.data"));
        assertThat(JsonShape.keyPaths(degraded)).as("完整键路径集合也必须完全相同（summary 没有数组）")
                .isEqualTo(healthyPaths);

        assertThat(readLong(degraded, "$.data.todayOrderCount")).isZero();
        assertThat(readLong(degraded, "$.data.todaySalesAmount")).isZero();
        assertThat(readLong(degraded, "$.data.waitShipCount")).isZero();
        assertThat(readLong(degraded, "$.data.refundPendingCount")).isZero();
        assertThat(readLong(degraded, "$.data.onShelfProductCount"))
                .as("**按域降级**：交易域挂了不影响商品域的数（这正是并行取数的意义）")
                .isEqualTo(DownstreamStubs.PRODUCT_ENABLED_COUNT);
        assertThat(readLong(degraded, "$.data.memberCount")).isEqualTo(DownstreamStubs.USER_MEMBER_COUNT);

        assertThat(logs.warnMessages()).as("每请求**恰好一条** WARN（把降级域收在一行里）").hasSize(1);
        assertThat(logs.warnMessages().get(0)).contains("endpoint=summary").contains("trade");
    }

    @Test
    @DisplayName("[§3 降级/读超时] 交易域停半路（>600ms 读超时）⇒ 同上：200/code=0/键不变/一条 warn")
    void summary_degradedOnReadTimeout() throws Exception {
        DownstreamStubs.TRADE.mode(DownstreamStubs.Mode.SLOW).slow(1200L);
        logs.clear();

        long start = System.nanoTime();
        String json = fetch(SUMMARY);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(readInt(json, "$.code")).isZero();
        assertThat(keysOf(json, "$.data")).containsExactlyInAnyOrderElementsOf(SUMMARY_KEYS);
        assertThat(readLong(json, "$.data.todayOrderCount")).isZero();
        assertThat(readLong(json, "$.data.onShelfProductCount")).isEqualTo(DownstreamStubs.PRODUCT_ENABLED_COUNT);
        assertThat(logs.warnMessages()).as("超时同样只留一条 warn").hasSize(1);
        assertThat(elapsedMs)
                .as("必须由**读超时**（600ms）终止，而不是等桩把 1200ms 睡完：实测 %dms", elapsedMs)
                .isLessThan(1150L);
    }

    @Test
    @DisplayName("[§3 降级] 三个域全挂 ⇒ 仍然 200/code=0/键不变/依旧**只有一条** warn（不是三条）")
    void summary_allThreeDown_singleWarn() throws Exception {
        DownstreamStubs.TRADE.mode(DownstreamStubs.Mode.NONZERO_CODE);
        DownstreamStubs.PRODUCT.mode(DownstreamStubs.Mode.NONZERO_CODE);
        DownstreamStubs.USER.mode(DownstreamStubs.Mode.NONZERO_CODE);
        logs.clear();

        String json = fetch(SUMMARY);

        assertThat(readInt(json, "$.code")).isZero();
        assertThat(keysOf(json, "$.data")).containsExactlyInAnyOrderElementsOf(SUMMARY_KEYS);
        assertThat(readLong(json, "$.data.todayOrderCount")).isZero();
        assertThat(readLong(json, "$.data.onShelfProductCount")).isZero();
        assertThat(readLong(json, "$.data.memberCount")).isZero();
        assertThat(logs.warns()).as("三个域都挂也**只留一条** WARN（条数不随故障数变化）").hasSize(1);
        assertThat(logs.warnMessages().get(0))
                .as("一条日志里要能看出**是哪几个域**挂了").contains("product").contains("trade").contains("user");
    }

    @Test
    @DisplayName("[§3 降级] 降级结果**不入缓存**：连续两次都打下游；恢复后数值回到正常（两个方向都测）")
    void degradedResultIsNotCached_andRecovers() throws Exception {
        DownstreamStubs.TRADE.mode(DownstreamStubs.Mode.NONZERO_CODE);

        String first = fetch(SUMMARY);
        String second = fetch(SUMMARY);
        assertThat(readLong(first, "$.data.todayOrderCount")).isZero();
        assertThat(readLong(second, "$.data.todayOrderCount")).isZero();
        assertThat(DownstreamStubs.TRADE.calls())
                .as("降级结果**绝不能**入缓存（否则一次瞬时故障会变成一分钟的对外错误结论）："
                        + "两次请求必须各打一次交易域")
                .isEqualTo(2);

        // 恢复方向：交易域回到正常 ⇒ 数值立刻回到真实值（且这时才允许写缓存）
        DownstreamStubs.TRADE.mode(DownstreamStubs.Mode.OK);
        String recovered = fetch(SUMMARY);
        assertThat(readLong(recovered, "$.data.todayOrderCount")).isEqualTo(DownstreamStubs.TRADE_TODAY_ORDER_COUNT);
        assertThat(readLong(recovered, "$.data.todaySalesAmount")).isEqualTo(DownstreamStubs.TRADE_TODAY_SALES_AMOUNT);
        assertThat(readLong(recovered, "$.data.onShelfProductCount")).isEqualTo(DownstreamStubs.PRODUCT_ENABLED_COUNT);

        // 恢复后才写缓存：再请求一次不再打下游
        fetch(SUMMARY);
        assertThat(DownstreamStubs.TRADE.calls()).as("恢复后的结果可以缓存").isEqualTo(3);
    }

    // ==================================================================
    // trend / top 的降级：空数组 + 键路径（含"元素键必然消失"的如实记录）
    // ==================================================================

    @Test
    @DisplayName("[§3 降级] trend：交易域不可用 ⇒ data 为空数组；信封与 data[] 键路径不变（元素键必然消失）")
    void trend_degradedIsEmptyArray_envelopeUnchanged() throws Exception {
        String healthy = fetch(TREND + "?days=3");
        assertThat(readList(healthy, "$.data")).hasSize(3);

        dashboardCache.evictAll();   // 否则会命中上一步的正常缓存（见 summary 用例的同一注释）
        DownstreamStubs.TRADE.mode(DownstreamStubs.Mode.NONZERO_CODE);
        logs.clear();
        String degraded = fetch(TREND + "?days=3");

        assertThat(readInt(degraded, "$.code")).isZero();
        assertThat(readList(degraded, "$.data")).as("缺的那部分是**空数组**（不是 null、不是 500）").isEmpty();
        assertThat(JsonShape.withoutElementPaths(JsonShape.keyPaths(degraded)))
                .as("信封(code/message/data)与 data[*] 的键路径必须与正常时一致")
                .isEqualTo(JsonShape.withoutElementPaths(JsonShape.keyPaths(healthy)));
        // 如实记录：正常时存在的元素键（data[*].date 等）在空数组里**不可能存在**——
        // 这是"缺项回空数组"这条要求的数学后果，不是实现漂移（见报告里的 keysDelta 列）
        assertThat(JsonShape.elementPaths(JsonShape.keyPaths(healthy)))
                .containsExactlyInAnyOrderElementsOf(TREND_ITEM_KEYS);
        assertThat(JsonShape.elementPaths(JsonShape.keyPaths(degraded))).isEmpty();
        assertThat(logs.warnMessages()).hasSize(1);
    }

    @Test
    @DisplayName("[§3 降级] top?type=sales：商品域不可用 ⇒ 空数组；**用户域挂掉时 top 的键路径逐字不变**")
    void topDegraded_emptyArrayWhenProductDown_andLiteralEqualityWhenOtherDomainDown() throws Exception {
        String healthy = fetch(TOP + "?type=sales&limit=5");

        // ① 行来源（商品域）不可用 ⇒ 空数组
        dashboardCache.evictAll();
        DownstreamStubs.PRODUCT.mode(DownstreamStubs.Mode.NONZERO_CODE);
        logs.clear();
        String productDown = fetch(TOP + "?type=sales&limit=5");
        assertThat(readInt(productDown, "$.code")).isZero();
        assertThat(readList(productDown, "$.data")).isEmpty();
        assertThat(JsonShape.withoutElementPaths(JsonShape.keyPaths(productDown)))
                .isEqualTo(JsonShape.withoutElementPaths(JsonShape.keyPaths(healthy)));
        assertThat(logs.warnMessages()).hasSize(1);

        // ② 与 top 无关的域（用户域）挂掉 ⇒ **完整键路径集合逐字不变**（这是"降级不变形"的最强形态：
        //    列表仍然非空，元素键也在，只是另一个域的数归 0）
        dashboardCache.evictAll();
        DownstreamStubs.PRODUCT.mode(DownstreamStubs.Mode.OK);
        DownstreamStubs.USER.mode(DownstreamStubs.Mode.NONZERO_CODE);
        logs.clear();
        int productCallsBefore = DownstreamStubs.PRODUCT.calls();
        String userDown = fetch(TOP + "?type=sales&limit=5");
        assertThat(readInt(userDown, "$.code")).isZero();
        assertThat(DownstreamStubs.PRODUCT.calls())
                .as("前置：本次必须是**真的重新取数**（缓存已换代），否则键路径断言是空的")
                .isEqualTo(productCallsBefore + 1);
        assertThat(JsonShape.keyPaths(userDown))
                .as("top 与用户域无关 ⇒ 键路径集合必须与正常时**完全相同**")
                .isEqualTo(JsonShape.keyPaths(healthy));
        assertThat(readLong(userDown, "$.data[0].value")).isEqualTo(DownstreamStubs.TOP_SPU1_SALES);
        assertThat(logs.warns()).as("top 不依赖用户域 ⇒ 本次请求不该有降级 WARN").isEmpty();
    }

    @Test
    @DisplayName("[§3 降级] top?type=amount：交易域不可用 ⇒ 空数组；商品域不可用 ⇒ 也空数组（不谎报「商品已删除」）")
    void topByAmount_degradation() throws Exception {
        String healthy = fetch(TOP + "?type=amount&limit=5");
        assertThat(readLong(healthy, "$.data[0].value")).as("amount 榜的 value = 销售额").isEqualTo(DownstreamStubs.TOP_SPU1_AMOUNT);
        assertThat(readString(healthy, "$.data[0].title")).as("标题来自商品域").isEqualTo("桩商品9101");

        dashboardCache.evictAll();
        DownstreamStubs.TRADE.mode(DownstreamStubs.Mode.NONZERO_CODE);
        logs.clear();
        String tradeDown = fetch(TOP + "?type=amount&limit=5");
        assertThat(readList(tradeDown, "$.data")).isEmpty();
        assertThat(logs.warnMessages()).hasSize(1);

        dashboardCache.evictAll();
        DownstreamStubs.TRADE.mode(DownstreamStubs.Mode.OK);
        DownstreamStubs.PRODUCT.mode(DownstreamStubs.Mode.NONZERO_CODE);
        logs.clear();
        String productDown = fetch(TOP + "?type=amount&limit=5");
        assertThat(readInt(productDown, "$.code")).isZero();
        assertThat(readList(productDown, "$.data"))
                .as("商品域（补标题/主图的那一半）不可用 ⇒ 空数组："
                        + "**绝不**把「商品服务挂了」显示成「商品已删除」（那是一个看起来正常的错误结论）")
                .isEmpty();
        assertThat(JsonShape.withoutElementPaths(JsonShape.keyPaths(productDown)))
                .isEqualTo(JsonShape.withoutElementPaths(JsonShape.keyPaths(healthy)));
    }

    @Test
    @DisplayName("[C1 保真] top?type=amount：调用**成功**但某个 SPU 查不到 ⇒ 仍是单体的「商品已删除」兜底")
    void topByAmount_missingSpuKeepsMonolithFallback() throws Exception {
        DownstreamStubs.PRODUCT.omitSpu(DownstreamStubs.TOP_SPU_ID_2);

        String json = fetch(TOP + "?type=amount&limit=5");

        assertThat(readInt(json, "$.code")).isZero();
        assertThat(readString(json, "$.data[1].title"))
                .as("与单体 AdminDashboardServiceImpl.topByAmount 的兜底文案逐字一致")
                .isEqualTo("商品已删除");
        assertThat(JsonPath.parse(json).read("$.data[1].mainImage", Object.class))
                .as("单体的兜底是 mainImage=null（这是**业务值**：商品真的没了，不是降级占位）")
                .isNull();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private String fetch(String url) throws Exception {
        MvcResult result = mockMvc.perform(gw(get(url)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> keysOf(String json, String path) {
        Map<String, Object> map = JsonPath.read(json, path);
        return new java.util.LinkedHashSet<>(map.keySet());
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
