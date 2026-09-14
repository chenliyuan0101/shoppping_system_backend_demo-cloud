package com.mall.product.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * mall-product 的真库测试基类（与 marketing / review / content 的薄基类同一套思路）。
 *
 * <h2>为什么必须连真库</h2>
 * 本批的**全部正确性**都押在两件事上：
 * <ol>
 *   <li><b>条件 UPDATE 的影响行数</b>（{@code skuMapper.update(null, wrapper)}，
 *       {@code WHERE status=1 AND stock >= n}）——谁抢到了库存、流水里的 before/after 对不对，
 *       任何 mock/fake DB 都证明不了；</li>
 *   <li><b>数据真的搬过来了</b>——6 张表逐表行数对齐（见 db/02 的自检），
 *       这里进一步用真实业务端点读到真实数据来证明（不是"表建出来了"就算）。</li>
 * </ol>
 * 所以连的是真的 {@code mall_product}，且并发用例**不用** {@code @Transactional} 回滚
 * （并发要求 UPDATE 真的落在别的连接上，"同一个未提交事务里自说自话"证明不了并发语义）；
 * 代价是必须自己清理（见 {@link #cleanUp()}）。
 *
 * <h2>为什么第一个断言是 `SELECT DATABASE()`</h2>
 * P6-1 最容易犯的错是"以为连了新库、其实还在老库"：单体连 {@code mall}、本服务连
 * {@code mall_product}，两者的表名**完全一样**（都叫 pms_spu…），所以连错库的用例会**全绿**，
 * 只是验证的是另一份数据。{@link #assertConnectedToProductSchema()} 在每个用例前问一次数据库，
 * 把这类假绿变成硬失败。
 *
 * <h2>两个令牌为什么必须写在 @SpringBootTest(properties=...) 上</h2>
 * profile 专属配置（{@code application-dev.yaml}）优先级高于
 * {@code src/test/resources/application.properties}，写进 properties 文件会被 dev 令牌盖掉，
 * 而"无令牌 → 403"的用例照样通过、把问题掩盖（P1~P5 各踩过一次）。
 * 这里的断言必须在**假密钥**下成立，才能证明"鉴权真的生效了"而不是"恰好两边一样"。
 *
 * <h2>测试数据怎么隔离</h2>
 *  · 库存/流水类用例：用**显式 skuId**（真实种子 SKU 2001）+ 自己造的 {@code orderNo} 前缀，
 *    用完 {@link #rememberStock(long)} 恢复库存/销量、按 orderNo 删掉自己写的流水行；</li>
 *  · 商品/类目/品牌类用例：用 {@code @Transactional + @Rollback}（照搬单体 PortalBrandMySqlTest
 *    的做法），用例结束由回滚清理；</li>
 *  · Redis 里被标记的"待同步"成员：只删自己加进去的那几个（见 {@link #forgetPendingSync(long...)}），
 *    **绝不**通配删除——{@code mall:es:pending} 是全站共用的（单体与 P6-2 的 search 都在用）。
 */
@SpringBootTest(properties = {
        "mall.internal.token=test-internal-token",
        "mall.gateway.auth-token=test-gateway-token",
        // P6-3：检索域地址**默认指向死端口**（本机端口 9 无人监听 ⇒ 连接立即被拒）。
        // 理由（与 P6-2 同一条纪律：单测的成败不该取决于"外面是否正好跑着某个服务"）：
        //   · 默认"检索不可用"⇒ 所有既有套件（后台 16 + 前台 4 + 库存并发…）跑的是**回落到 MySQL** 的路径，
        //     这是它们原本就在验的行为，也是 P6-1 期它们所处的状态；
        //   · 要验"检索可用"的套件**显式**覆盖这个值：用桩 HTTP 服务（SearchRemoteStubMySqlTest）
        //     或真实 8103（SearchRemoteIntegrationTest，带 assumeTrue）。
        // ⚠️ 这不是"测试专用开关"：闸门/回落逻辑一个字都没改，改的只是"下游地址"，
        //    与生产上用 MALL_SEARCH_BASE_URL 指向另一个实例是同一条配置。
        "mall.search.base-url=${MALL_SEARCH_BASE_URL:http://127.0.0.1:9}",
        // P6-5 #1：MQ 同步通道**默认关**，理由与上面同一条纪律：
        //   · 用例跑的是"MQ 不可用 ⇒ 回落 Redis 待同步集合"这条既有路径（P6-1~P6-4 期它们就是这样），
        //     行为零变化；否则每个用例都会往真 broker 投消息、由 search 真去写 ES，
        //     单测就被外部服务绑架，还会在索引里留下"用例中间态"的文档（P6-4 探针踩过）。
        //   · MQ 通道本身由两条更结实的证据守着：ProductSyncPublisherTest（桩 broker 验 JSON/路由键/分批/兜底）
        //     与主代理的**活体测量**（真 8102 + 真 broker + 真 ES，测"变更到索引可见"的毫秒数）。
        "mall.mq.enabled=false"
})
@AutoConfigureMockMvc
public abstract class ProductTestBase {

    protected static final String TOKEN_HEADER = "X-Internal-Token";
    protected static final String TOKEN = "test-internal-token";

    /** 本服务自己的库名：所有真库断言都必须在它上面发生 */
    protected static final String EXPECTED_SCHEMA = "mall_product";

    /** 测试里代表"当前管理员"的 id（令牌的 sub；本服务的后台端点不落"操作人"字段，故只需一致即可） */
    protected static final long ADMIN_ID = 1L;

    @Autowired
    protected MockMvc mockMvc;

    /** 运行时配置里的 JWT 验签密钥（**不是**测试专用值，见 {@link #adminToken()}） */
    @Value("${mall.jwt.secret:}")
    protected String jwtSecret;

    private String adminToken;

    /** 指向本服务自己的库（mall_product）——"数据是不是真的搬过来了"就靠它断言 */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** Redis 直连（**仅供"待同步标记"用例断言/清理**；业务代码只经 support/CacheService） */
    @Autowired
    protected StringRedisTemplate redisTemplate;

    /** 每个测试实例一个唯一后缀：避免并发/重复运行时撞名（照搬单体 MySqlTestBase 的做法） */
    protected final String suffix = String.valueOf(System.nanoTime() % 1_000_000L);

    private static final AtomicLong SEQ = new AtomicLong(1);

    /** 本用例造出来的 orderNo（清理流水行用） */
    protected final List<String> ownedOrderNos = new ArrayList<>();

    /** [skuId, stock, sales] 基线（@AfterEach 恢复） */
    private final List<long[]> skuBaselines = new ArrayList<>();

    /** [spuId, sales] 基线 */
    private final List<long[]> spuBaselines = new ArrayList<>();

    /** 本用例往 mall:es:pending 里加过的 spuId（只删这几个） */
    private final List<Long> markedPendingSpuIds = new ArrayList<>();

    protected static long nextSeq() {
        return SEQ.getAndIncrement();
    }

    @BeforeEach
    protected void assertConnectedToProductSchema() {
        assertEquals(EXPECTED_SCHEMA, schemaName(),
                "真库用例必须连 " + EXPECTED_SCHEMA + "（连到 mall 会变成'验证另一份数据'的假绿）");
    }

    @AfterEach
    protected void cleanUp() {
        // ① 删掉本用例写的库存流水（保持新库与源库的**逐表行数对齐**不被测试污染）
        for (String orderNo : ownedOrderNos) {
            jdbcTemplate.update("DELETE FROM " + EXPECTED_SCHEMA + ".pms_sku_stock_log WHERE order_no = ?", orderNo);
        }
        ownedOrderNos.clear();
        // ② 恢复库存/销量/SPU 销量基线（并发用例会把它们改掉）
        for (long[] b : skuBaselines) {
            jdbcTemplate.update("UPDATE " + EXPECTED_SCHEMA + ".pms_sku SET stock = ?, sales = ? WHERE id = ?",
                    (int) b[1], (int) b[2], b[0]);
        }
        skuBaselines.clear();
        for (long[] b : spuBaselines) {
            jdbcTemplate.update("UPDATE " + EXPECTED_SCHEMA + ".pms_spu SET sales = ? WHERE id = ?",
                    (int) b[1], b[0]);
        }
        spuBaselines.clear();
        // ③ 只摘掉自己加进"待同步"集合的成员（不做通配删除：那是全站共用的集合）
        if (!markedPendingSpuIds.isEmpty()) {
            try {
                redisTemplate.opsForSet().remove(CacheKeys.esPendingSync(),
                        markedPendingSpuIds.stream().map(String::valueOf).toArray());
            } catch (Exception ignored) {
                // Redis 不可用时本来也没写进去
            }
            markedPendingSpuIds.clear();
        }
    }

    // ==================================================================
    // 库/边界的读断言辅助
    // ==================================================================

    /** 当前连接的库名；"配置写对了但 profile 没生效"是最容易犯的错，所以直接问数据库 */
    protected String schemaName() {
        return jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
    }

    protected long countOf(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    protected Integer intOf(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    protected String stringOf(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    /** SKU 当前库存（新库真值） */
    protected int stockOf(long skuId) {
        Integer s = intOf("SELECT stock FROM " + EXPECTED_SCHEMA + ".pms_sku WHERE id = ?", skuId);
        return s == null ? -1 : s;
    }

    protected int skuSalesOf(long skuId) {
        Integer s = intOf("SELECT sales FROM " + EXPECTED_SCHEMA + ".pms_sku WHERE id = ?", skuId);
        return s == null ? -1 : s;
    }

    protected int spuSalesOf(long spuId) {
        Integer s = intOf("SELECT sales FROM " + EXPECTED_SCHEMA + ".pms_spu WHERE id = ?", spuId);
        return s == null ? -1 : s;
    }

    /** 记录 SKU 的库存与销量基线（会在 {@link #cleanUp()} 里恢复） */
    protected void rememberStock(long skuId) {
        skuBaselines.add(new long[]{skuId, stockOf(skuId), skuSalesOf(skuId)});
    }

    /** 记录 SPU 销量基线（会在 {@link #cleanUp()} 里恢复） */
    protected void rememberSpuSales(long spuId) {
        spuBaselines.add(new long[]{spuId, spuSalesOf(spuId)});
    }

    protected void assertStock(long skuId, int expected) {
        assertEquals(expected, stockOf(skuId), "SKU " + skuId + " 的库存不符");
    }

    /** 造一个本用例专属的订单号（幂等键，同时是流水行的检索条件） */
    protected String newOrderNo(String tag) {
        String orderNo = "P6IT" + tag + "-" + suffix + "-" + nextSeq();
        ownedOrderNos.add(orderNo);
        return orderNo;
    }

    /** 登记"这个 spuId 是本用例标记进待同步集合的"（清理用） */
    protected void trackPendingSync(long... spuIds) {
        for (long id : spuIds) {
            markedPendingSpuIds.add(id);
        }
    }

    protected boolean redisUp() {
        try {
            redisTemplate.opsForValue().set("mall:test:ping", "pong", Duration.ofSeconds(10));
            return "pong".equals(redisTemplate.opsForValue().get("mall:test:ping"));
        } catch (Exception e) {
            return false;
        }
    }

    // ==================================================================
    // 缓存清理（真库用例的通用前置）
    // ==================================================================

    /**
     * 清商品域的读缓存。
     *
     * <p>为什么真库用例需要它：把商品/类目/品牌改成"事务提交后才失效缓存"之后
     * （{@code AdminProductServiceImpl#evictOnCommit}：若在事务内就删，删完到提交之间若有并发读，
     * 会把库里的旧值重新读出来写回缓存），**带 {@code @Rollback} 的用例永远不会触发那次失效**
     * ——测试事务不提交。所以这类用例必须自己补一次。
     * <p>真实的"提交后失效"路径由 P6-4 的端到端探针覆盖（本批不切流量，没有活体链路）。
     * <p>⚠️ 只清商品域自己的 key，**不**用 {@code mall:cache:*} 通配：这个 Redis 是全站共用的，
     * 通配会顺手清掉会员状态缓存（{@code mall:cache:member:status:*}）等别人的 key。
     */
    protected void clearProductPortalCache() {
        try {
            for (String pattern : new String[]{
                    "mall:cache:home:index", "mall:cache:category:tree", "mall:cache:brand:list",
                    "mall:cache:product:ver", "mall:cache:product:detail:*", "mall:cache:product:shelf:*"}) {
                java.util.Set<String> keys = redisTemplate.keys(pattern);
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            }
        } catch (Exception ignored) {
            // Redis 不可用时缓存本来就没生效，无需清理（业务是 fail-open 的）
        }
    }

    // ==================================================================
    // HTTP 辅助（内部接口）
    // ==================================================================

    /**
     * <b>后台请求的统一入口（P6-1b 之后）</b>：路径以 {@code /api/admin} 开头时，
     * 自动带上"用**运行时配置里的密钥**本地签发的管理员令牌"。
     *
     * <p>为什么要有它：P6-1b 给 {@code /api/admin/**} 装上了鉴权闸，于是原来那些
     * "匿名打后台端点"的业务用例会全部 401。这些用例的意图是**业务链路**（建商品/上下架/查列表），
     * 不是鉴权，所以令牌在这里统一带上，调用点保持一行。
     *
     * <p>⚠️ 三条纪律：
     * <ol>
     *   <li><b>不是测试期开关</b>：闸门照常生效，令牌是真令牌（用部署的那把密钥签的）；
     *       想验"没令牌会怎样"，就用 {@code mockMvc.perform(...)} **直接**发请求 —— 绕过本包装，
     *       这样"无令牌"场景是真的没带令牌（见 {@code AdminAuthGateMySqlTest}）；</li>
     *   <li>只对 {@code /api/admin} 前缀加头：{@code /internal/**}、会员侧端点的行为一个字不改；</li>
     *   <li>调用方自己已经设过 {@code Authorization} 时不会被覆盖（头是"加"不是"改"）。</li>
     * </ol>
     */
    protected org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder builder)
            throws Exception {
        return mockMvc.perform(builder.with(request -> {
            String uri = request.getRequestURI();
            if (uri != null && uri.startsWith("/api/admin")) {
                request.addHeader("Authorization", "Bearer " + adminToken());
            }
            return request;
        }));
    }

    /**
     * 管理员令牌：用**运行时配置里的** {@code mall.jwt.secret} 本地签发（每实例一次，缓存）。
     *
     * <p>刻意**不**在 {@code @SpringBootTest(properties=...)} 里覆盖成"测试用密钥"：
     * 那样只能证明"测试自己签的自己认"，而覆盖不了真实部署配置。
     * 这里读的就是 dev/prod 配置的那把 —— 密钥配错（与单体不同）时本套件会红，正是想要的效果。
     */
    protected String adminToken() {
        if (adminToken == null) {
            adminToken = AdminTokenMinter.admin(jwtSecret, ADMIN_ID);
        }
        return adminToken;
    }

    /** 带内部令牌的 JSON POST */
    protected MockHttpServletRequestBuilder internalPost(String path, String json) {
        return post(path).header(TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    /** **不带**内部令牌的 JSON POST（"无令牌 → 403"的用例专用） */
    protected MockHttpServletRequestBuilder anonymousPost(String path, String json) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }
}
