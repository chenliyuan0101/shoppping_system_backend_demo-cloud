package com.mall.demo.support;

import com.jayway.jsonpath.JsonPath;
import com.mall.demo.common.JwtUtil;
import com.mall.demo.common.TokenVersionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真库集成测试基类：集中提供各套件重复使用的 helper（造会员/登录身份/建地址/加购物车/库存销量基线/下单支付）
 * 与统一的基线恢复逻辑(cleanUpBaselines)。
 *
 * <p>约定：子类只保留自己的业务断言与"套件特有"的清理，@AfterEach 里调用 cleanUpBaselines()。
 *
 * <h2>P3-4：会员 fixture 为什么直接写 {@code mall_user} 库的 SQL</h2>
 * 会员/地址/购物车/收藏/足迹已经整批搬进 {@code mall-user-center}（schema {@code mall_user}），
 * 单体里既没有 {@code /api/auth/register}、{@code /api/cart/**}、{@code /api/address/**} 这些接口，
 * 也没有 {@code ums_member} 等表（P3-5 之后 {@code ums_notification} 也不再由单体写：
 * 通知的消费者已搬到 {@code mall-user-center}，单体只保留订单日统计副作用）。
 * 因此本基类改成：
 * <ul>
 *   <li><b>造会员</b>：直接 {@code INSERT INTO mall_user.ums_member}（唯一索引 {@code uk_username} 保证用例隔离）；</li>
 *   <li><b>造地址 / 造购物车明细</b>：直接写 {@code mall_user.ums_address} / {@code mall_user.ums_cart_item}
 *       ——它们现在是下单链路的输入（{@code OrderServiceImpl} 经域契约读 user-center）；</li>
 *   <li><b>造登录身份</b>：<b>不</b>调 {@code /api/auth/*}（单体已无此接口，登录发生在 user-center），
 *       而是用本进程的 {@link JwtUtil} 按"会员 id + 当前令牌版本号"现签一个 {@code typ=user} 的 token。
 *       这与 user-center 登录签发的 token <b>完全同构</b>（同一把密钥、同一套 claims 口径），
 *       因此各套件里原有的 {@code Authorization: Bearer <token>} 调用方式一个字都不用改，
 *       而且顺带把 {@code MemberSession} 的"自行验签回退路径"真正跑起来了。</li>
 * </ul>
 * 这些 SQL 是<b>刻意为之的测试专用耦合</b>：真库套件需要一个"已知身份的会员"作为输入，
 * 而它的属主在另一个服务里；让 fixture 直接落到属主库里，比在单体里为测试保留一份会员实现更干净
 * （那份实现正是本次要删掉的东西）。测试期的<b>读</b>侧替身见 {@link UserCenterTestDoubleConfig}。
 *
 * <h2>测试期属性为什么写在注解里</h2>
 * {@code @TestPropertySource} 的内联属性优先级<b>高于</b> profile 专属配置文件
 * （{@code application-dev.yaml} 里配了 {@code mall.gateway.auth-token}，且 {@code spring.profiles.active=dev}
 * 在测试里同样生效），因此"测试专用值"必须写在测试类的注解上，写进
 * {@code src/test/resources/application.properties} 会被 profile 文件盖掉。
 * 放在基类上而不是每个套件上：{@code @TestPropertySource} 会被子类继承，
 * 而 {@code @SpringBootTest} 不会（子类自己声明时会把基类的 properties 整个替换掉）。
 */
@SpringBootTest   // 测试期配置见 src/test/resources/application.properties(限流关闭等)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // =====================================================================
        // P8-4：**运行态已换成最小权限账号 `mall_trade`**（只对 `mall_trade`.* 有权限），
        //   而真库套件的 fixture 必须**跨库写**：`mall_user.ums_member/ums_address/ums_cart_item`、
        //   `mall_product.pms_sku`(+`pms_sku_stock_log`)、`mall_review.pms_comment`，
        //   并断言 `mall.ums_notification` —— 最小权限账号读不了这些库（实测 `ERROR 1142`）。
        //   ⇒ 口径：**服务跑最小权限、测试夹具用管理员**。
        //   ⚠️ 这两行**必须写在这里**（`@TestPropertySource` 内联属性的优先级最高）。
        //      第一版我写在 `src/test/resources/application.properties` ⇒ **被 profile 专属文件盖掉**
        //      （`spring.profiles.active=dev` 生效时 `application-dev.yaml` 优先级**高于** test classpath 的
        //      `application.properties`）⇒ 45 个用例报 `ERROR 1142`，对外症状是
        //      `BadSqlGrammar ... INSERT INTO mall_user.ums_member`
        //      （SQLState 42000 被 Spring 归到"语法错误"，是最容易误判的一类权限报错）。
        // =====================================================================
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        // 网关身份凭据的测试值：GatewayIdentityMySqlTest 用它证明"网关注入的身份被信任"
        "mall.gateway.auth-token=test-gateway-token",
        // 内部接口凭据的测试值（/internal/** 由 InternalApiMySqlTest 断言）
        "mall.internal.token=test-internal-token",
        // 远程模式在测试里必须关闭：进程内没有 user-center 可调，
        // 4 个会员域契约由测试替身 UserCenterTestDoubleConfig 提供（生产恒为 true）
        "mall.user-center.remote=false",
        // 营销域接线同理退让：券契约由测试替身 MarketingTestDoubleConfig 顶上
        // （preview 会无条件取一次"可用券"，没有它任何走预览的套件都会 500）
        "mall.marketing.remote=false",
        // 商品域接线同理退让：远程模式在测试里必须关闭——进程内没有 mall-product 可调，
        // 3 个商品域契约（ProductQueryService/ProductStatQueryService/StockCommandService）
        // 由测试替身 ProductTestDoubleConfig 提供（生产恒为 true：main 的 yaml 不出现该键）
        "mall.product.remote=false",
        // ⚠️ 测试期把 Hikari 连接池压到 4（默认 10）——**这是环境的硬约束，不是性能优化**：
        //    Spring 按"上下文键"缓存 ApplicationContext，每个上下文各自持有一个 Hikari 池，
        //    直到 JVM 退出都不释放（30 多个套件 = 十几个上下文 = 一百多条连接）。
        //    本机 MySQL 的 max_connections=151，实测跑到最后几个套件时
        //    Max_used_connections 已达 152 > 151 → **恰好那一刻需要新连接的用例**报
        //    `Failed to obtain JDBC Connection`，对外表现为某个 500，
        //    而**单独跑那一个套件是绿的**——这是最难查的一类"偶发红"（取决于前面跑过多少上下文）。
        //    压到 4 后峰值降到上限一半以下；并发用例（ConcurrentStockMySqlTest）只是排队等连接，
        //    语义不受影响（它断言"不超卖"，不是"并行度"）。
        "spring.datasource.hikari.maximum-pool-size=4"
})
public abstract class MySqlTestBase {

    /** 会员 fixture 的统一初始密码（bcrypt 入库；用例里没有"输密码登录"这一步，仅保证列有合法值） */
    protected static final String MEMBER_PASSWORD = "Abc123456";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** Redis 直连(仅供 Redis 相关套件断言/清理缓存；业务代码禁止直接注入 RedisTemplate) */
    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected JwtUtil jwtUtil;

    @Autowired
    protected TokenVersionService tokenVersionService;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    /** 用例唯一后缀(避免用户名/数据冲突) */
    protected final String suffix = String.valueOf(System.nanoTime() % 1_000_000L);

    /** 当前用例注册的会员(registerAs 时写入) */
    protected long memberId;
    protected String username;

    /** 本用例注册过的全部会员 id(支持一个用例注册多个账号) */
    protected final List<Long> createdMemberIds = new ArrayList<>();

    /** 同一用例内第几次调 registerAs：第 2 个及以后的会员在用户名后缀再加序号，避免撞 uk_username */
    private int memberSeq = 0;

    /** 用例下过的订单号(用于清理) */
    protected final List<String> orderNos = new ArrayList<>();

    private final List<String[]> restoreStock = new ArrayList<>();       // [skuId, stock]
    private final List<String[]> restoreSales = new ArrayList<>();       // [table, id, sales]
    // 券的"已发量"基线随券一起删除：见下方 rememberCouponCount 处的说明

    // ==================== 账号 ====================

    /**
     * 造一个会员并返回它的登录身份（token）。同时写入 {@link #memberId} / {@link #username}。
     *
     * <p>实现见类注释：会员行直插 {@code mall_user.ums_member}（字段口径与 user-center 的注册一致——
     * 昵称缺省取用户名、状态启用、密码 BCrypt），token 由本地 {@link JwtUtil} 按
     * "memberId + 当前令牌版本号"签发，与 user-center 登录签发的 token 同构。
     *
     * @return 可直接放进 {@code Authorization: Bearer } 的裸 token
     */
    protected String registerAs(String prefix) throws Exception {
        // 一个用例里造多个会员时（如"换一个新会员再领券"）用户名必须互不相同
        username = prefix + suffix + (memberSeq++ == 0 ? "" : "_" + memberSeq);
        jdbcTemplate.update("INSERT INTO mall_user.ums_member (username, password, nickname, status, gender)"
                        + " VALUES (?, ?, ?, 1, 0)",
                username, passwordEncoder.encode(MEMBER_PASSWORD), username);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM mall_user.ums_member WHERE username = ?", Long.class, username);
        memberId = id == null ? 0L : id;
        createdMemberIds.add(memberId);
        long ver = tokenVersionService.current(TokenVersionService.TYPE_USER, memberId);
        return jwtUtil.createToken(memberId, username, JwtUtil.TYPE_USER, ver);
    }

    /** 管理员**登录**已经不在单体（P7 起 {@code /api/admin/auth/**} 直路由 {@code mall-admin}） */
    // P8-2a：原来这里的 loginAdmin() 打的是单体自己的 POST /api/admin/auth/login，
    // 而单体侧那套登录端点（controller/service/实体/mapper）已随"管理端身份统一到网关"一并删除
    // ⇒ 后台用例统一改用 adminHeaders()（网关注入的身份三件套），见下方。

    // ==================== 管理端身份（P8-2a：只信网关注入的头） ====================

    /**
     * 网关注入的管理端身份头名与共享凭据。
     *
     * <p>值必须与上面 {@code @TestPropertySource} 里的 {@code mall.gateway.auth-token}
     * 逐字一致（网关与各服务共用同一把凭据）。
     */
    protected static final String GW_AUTH_HEADER = "X-Gateway-Auth";
    protected static final String GW_ADMIN_ID_HEADER = "X-Admin-Id";
    protected static final String GW_ADMIN_VER_HEADER = "X-Admin-Ver";
    protected static final String GW_SECRET = "test-gateway-token";

    /**
     * 测试里代表"当前管理员"的 id（= 网关从管理端 JWT 的 {@code sub} 取到的值）。
     *
     * <p>P8-2a 起单体只把这个 id 转发成 {@code AuthAttribute.ADMIN_USER_ID}
     * （后台退款/发货的"操作人"字段用它），**不再**去账号表里核对它是否存在——
     * 因此这里用一个固定值即可，不需要在真库里造管理员。
     */
    protected static final long ADMIN_ID = 1L;

    /**
     * 后台请求的身份三件套 —— **真实链路上由网关的 {@code AdminIdentityFilter} 验签后注入**
     * （{@code X-Gateway-Auth} + {@code X-Admin-Id} + {@code X-Admin-Ver}）。
     *
     * <p>为什么测试直接给这三个头，而不是"先登录拿令牌再带上 Authorization"：
     * 单体**已经不再验签**（P8-2a：{@code AdminIdentityResolver} 只认这三个头），
     * 拿令牌来打 MockMvc 只会得到 {@code 401 未登录}；令牌 → 头的转换发生在网关里，
     * 网关自己那一步由 {@code mall-gateway} 的 {@code AdminIdentityFilterTest}（25 例）与活体 C1 守着。
     *
     * <p>不调本方法的 {@code /api/admin/**} 请求 = **直连端口**（没有网关注入身份）⇒ 必须 401「未登录」。
     */
    protected HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(GW_AUTH_HEADER, GW_SECRET);
        headers.add(GW_ADMIN_ID_HEADER, String.valueOf(ADMIN_ID));
        headers.add(GW_ADMIN_VER_HEADER, "0");
        return headers;
    }

    // ==================== 地址 / 购物车（属主库 = mall_user） ====================

    /**
     * 给"当前用例的会员"建一个默认收货地址，返回地址 id。
     *
     * <p>改造前后这里调 {@code POST /api/address}（单体接口，现已随地址域搬去 user-center）；
     * 现在直接写属主库。参数 {@code token} 保留只为不改动各套件的调用点，
     * 语义上"地址属于 {@link #memberId} 当前指向的会员"。
     */
    protected long createAddress(String token) throws Exception {
        jdbcTemplate.update("INSERT INTO mall_user.ums_address (member_id, receiver_name, receiver_phone,"
                        + " province_code, province_name, city_code, city_name, district_code, district_name,"
                        + " detail, is_default) VALUES (?, '张三', '13800138000', '110000', '北京市', '110100',"
                        + " '北京市', '110105', '朝阳区', '建国路 88 号', 1)",
                memberId);
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM mall_user.ums_address WHERE member_id = ?", Long.class, memberId);
        return id == null ? 0L : id;
    }

    /**
     * 给当前会员加一条购物车明细（{@code checked=1}），返回条目 id。
     *
     * <p>购物车下单（{@code source=CART}）的输入；原先靠 {@code POST /api/cart/add}，现在直接写属主库。
     * {@code spu_id} 由 SKU 反查（它是购物车展示用的冗余列，测试只需要它非空且正确）。
     * 若同一 SKU 已有条目则累加数量（与 {@code uk_member_sku} 唯一键语义一致）。
     */
    protected long addCartItem(long skuId, int quantity) {
        return addCartItem(memberId, skuId, quantity);
    }

    protected long addCartItem(long ownerId, long skuId, int quantity) {
        Long spuId = jdbcTemplate.queryForObject("SELECT spu_id FROM mall_product.pms_sku WHERE id = ?", Long.class, skuId);
        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM mall_user.ums_cart_item"
                + " WHERE member_id = ? AND sku_id = ?", Integer.class, ownerId, skuId);
        if (exists != null && exists > 0) {
            jdbcTemplate.update("UPDATE mall_user.ums_cart_item SET quantity = quantity + ?, checked = 1"
                    + " WHERE member_id = ? AND sku_id = ?", quantity, ownerId, skuId);
        } else {
            jdbcTemplate.update("INSERT INTO mall_user.ums_cart_item (member_id, spu_id, sku_id, quantity, checked)"
                    + " VALUES (?, ?, ?, ?, 1)", ownerId, spuId == null ? 0L : spuId, skuId, quantity);
        }
        Long id = jdbcTemplate.queryForObject("SELECT id FROM mall_user.ums_cart_item"
                + " WHERE member_id = ? AND sku_id = ?", Long.class, ownerId, skuId);
        return id == null ? 0L : id;
    }

    /** 当前会员的购物车条目数（原 {@code GET /api/cart/count} 的口径：按明细条数，不是件数） */
    protected int cartItemCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mall_user.ums_cart_item WHERE member_id = ?", Integer.class, memberId);
        return n == null ? 0 : n;
    }

    /**
     * 按<b>当前令牌版本号</b>再签一个 token（等价于"重新登录拿到的新令牌"）。
     *
     * <p>P3-4 起登录发生在 user-center，单体侧拿不到新 token；但"禁用/改密/登出后版本号 +1，
     * 新签发的 token 可用"这条语义仍然只由单体侧校验，因此需要一个本地签发入口。
     */
    protected String mintTokenForCurrentMember() {
        return jwtUtil.createToken(memberId, username, JwtUtil.TYPE_USER,
                tokenVersionService.current(TokenVersionService.TYPE_USER, memberId));
    }

    // ==================== 库存 / 销量基线 ====================

    protected int stock(long skuId) {
        Integer v = jdbcTemplate.queryForObject("SELECT stock FROM mall_product.pms_sku WHERE id = " + skuId, Integer.class);
        return v == null ? -1 : v;
    }

    protected void rememberStock(long skuId) {
        restoreStock.add(new String[]{String.valueOf(skuId), String.valueOf(stock(skuId))});
    }

    protected int skuSales(long skuId) {
        Integer v = jdbcTemplate.queryForObject("SELECT sales FROM mall_product.pms_sku WHERE id = " + skuId, Integer.class);
        return v == null ? -1 : v;
    }

    /**
     * 记录销量基线：{@code table} 传<b>不带库名</b>的表名（{@code mall_product.pms_sku} / {@code mall_product.pms_spu}）。
     *
     * <p>读与写必须落在同一个库上：术后商品数据的属主是 {@code mall_product}，
     * 而本类所在的连接默认库是 {@code mall}——那里还留着 P6 迁移前的<b>冻结副本</b>。
     * 少写一次 {@code mall_product.} 前缀就是"读冻结副本、写属主库"的错配基线
     * （副本值恰好相等时静默正确，一旦不等就会在回写时把销量改错）。
     * 库名只在 {@code cleanUpBaselines()} 与这里各拼一次，调用方**不要**自带前缀（会拼成双前缀）。
     */
    protected void rememberSales(String table, long id) {
        // ⚠️ 守卫（P6-4 加，**反向**）：实参**不要带库名** —— 本方法内部会拼 `mall_product.`，
        //    调用方若自带前缀就会拼成 `mall_product.pms_sku`（SQL 直接报错）。
        //    为什么写成会红的断言：这类"多写一次前缀"的疏漏在正常情况下不会报错、
        //    只会静默落到 mall 库的**冻结副本**上（基线取错表、回写也回错表），
        //    与"漏删 @Autowired 被编译器抓到"是同一个思路：把自己的疏漏变成会红的东西。
        if (table != null && table.contains(".")) {
            throw new AssertionError("table 不要带库名（本方法内部拼 mall_product.），否则会拼成双前缀：" + table);
        }
        Integer v = jdbcTemplate.queryForObject(
                "SELECT sales FROM mall_product." + table + " WHERE id = " + id, Integer.class);
        restoreSales.add(new String[]{table, String.valueOf(id), String.valueOf(v == null ? 0 : v)});
    }

    protected void assertStock(long skuId, int expected) {
        int actual = stock(skuId);
        if (actual != expected) {
            throw new AssertionError("sku " + skuId + " stock expected " + expected + " but was " + actual);
        }
    }

    protected void assertSkuSales(long skuId, int expected) {
        int actual = skuSales(skuId);
        if (actual != expected) {
            throw new AssertionError("sku " + skuId + " sales expected " + expected + " but was " + actual);
        }
    }

    /** 语义别名：SKU 销量断言(部分套件沿用的命名) */
    protected void assertSales(long skuId, int expected) {
        assertSkuSales(skuId, expected);
    }

    /**
     * <b>P5 步骤 C 删除</b>：原来这里有 {@code rememberCouponCount(templateId)}
     * （记录券模板的"已发量"基线，供 {@link #cleanUpBaselines()} 复原）。
     *
     * <p>它随券一起消失了：券的读写（含领券写已发量）整体搬去 {@code mall-marketing}，
     * 单体侧没有任何用例会再改券的已发量——留着它只会指向"没人写的那一份"，
     * 而且它会让 grep 券表名 在单体里仍然有命中，
     * 破坏"单体不再有任何券表引用"这条可机器验证的边界（步骤 C 的验收项之一）。
     *
     * <p>如果将来真有单体用例需要造券数据：那是**测试替身**的事（见 {@code CouponMySqlTest}
     * 用 {@code @MockitoBean} 替换券契约），不是去直连别人的库。
     */

    // ==================== 商品索引增量同步：P6-4 已随域搬走 ====================
    // 这里原来有 drainIndexSyncQueue() / drainIndexSyncQueueUntil()：它们注入单体的
    // ProductSearchSyncTask.flushPending() 去消费 Redis 集合 mall:es:pending。
    // P6-4（D5）删掉了单体侧的消费者与定时任务（"索引双写者"收口）⇒ 这两个 helper 已无调用对象，
    // 且它们的唯一使用者是随域搬走的 pms 套件（ES 同步 / 增量同步）。
    // 队列现在由 mall-search 的 ProductSearchSyncTask 消费；"索引是否收敛"的判据也在那边
    // （content/ProductIndexIncrementFallbackEsTest、mq/ProductSyncConsumerTest）。
    // 刻意**删掉而不是留空实现**：留一个"什么都不做"的 drain 会让人以为队列还有人管。

    // ==================== Redis(仅 Redis 相关套件使用) ====================

    /** Redis 是否可用：Redis 用例统一用 {@code assumeTrue(redisUp())} 守卫(没装 Redis 则跳过而非失败) */
    protected boolean redisUp() {
        try {
            redisTemplate.opsForValue().set("mall:test:ping", "pong", Duration.ofSeconds(10));
            return "pong".equals(redisTemplate.opsForValue().get("mall:test:ping"));
        } catch (Exception e) {
            return false;
        }
    }

    /** 某个 Redis key 是否存在 */
    protected boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    // ==================== 下单 / 支付 ====================

    /**
     * 立即购买下单(数量 qty)，返回订单号并登记清理。
     *
     * <p>语义未变：{@code source=BUY_NOW} 不读购物车，但仍然要读一次收货地址
     * （{@code OrderServiceImpl.requireAddress} → 地址域契约 → user-center），
     * 所以调用前必须先用 {@link #createAddress(String)} 造出地址。
     */
    protected String buyNow(String token, long addressId, long skuId, int qty) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/order/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"BUY_NOW\",\"addressId\":" + addressId
                                + ",\"buyNow\":{\"skuId\":" + skuId + ",\"quantity\":" + qty + "}}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return trackOrder(r);
    }

    /** 模拟支付(成功) */
    protected void pay(String auth, String orderNo) throws Exception {
        mockMvc.perform(post("/api/pay/mock").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + orderNo + "\",\"success\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 从下单响应里取订单号并登记 */
    protected String trackOrder(MvcResult r) throws Exception {
        String orderNo = JsonPath.read(r.getResponse().getContentAsString(), "$.data");
        orderNos.add(orderNo);
        return orderNo;
    }

    // ==================== 基线恢复（子类 @AfterEach 调用） ====================

    /**
     * 通用清理：恢复库存/销量/券已领数基线，删除本用例产生的订单相关数据与自建会员及其资产。
     * 子类如需额外清理，在自己的 cleanUp() 里补充。
     */
    protected void cleanUpBaselines() {
        for (String[] s : restoreStock) {
            jdbcTemplate.update("UPDATE mall_product.pms_sku SET stock = ? WHERE id = ?",
                    Integer.parseInt(s[1]), Long.parseLong(s[0]));
        }
        for (String[] s : restoreSales) {
            // table 实参**不带库名**（rememberSales 有反向守卫）⇒ 这里拼一次库名，读与写各拼一次、不重不漏
            jdbcTemplate.update("UPDATE mall_product." + s[0] + " SET sales = ? WHERE id = ?",
                    Integer.parseInt(s[2]), Long.parseLong(s[1]));
        }

        for (String orderNo : orderNos) {
            jdbcTemplate.update("DELETE FROM oms_refund WHERE order_no = ?", orderNo);
            jdbcTemplate.update("DELETE FROM oms_order_item WHERE order_no = ?", orderNo);
            jdbcTemplate.update("DELETE FROM oms_payment WHERE order_no = ?", orderNo);
            jdbcTemplate.update("DELETE FROM mall_product.pms_sku_stock_log WHERE order_no = ?", orderNo);
            jdbcTemplate.update("DELETE FROM oms_order WHERE order_no = ?", orderNo);
        }
        deleteMemberAssets();
        reclaimOrphanOrders();
    }

    /**
     * 兜底回收：会员已被删除(测试自建账号)但订单还留在库里的"孤儿订单"。
     *
     * <p>为什么需要：某些用例(如并发生成订单、外部依赖失败中断)可能漏登记订单号，
     * 会员删掉后订单就成了垃圾；跑多轮后这些订单会被超时任务取消并**回补库存**，
     * 导致种子 SKU 的库存慢慢偏离基线（曾把 sku 2001 从 50 顶到 59，打红别的用例）。
     *
     * <p>处理：仅回收最近 1 天、非批量演示(单号不以 88 开头)、会员已不存在的订单；
     * 对"库存仍处于占用状态"的订单(待支付/待发货/待收货/退款中)先按明细回补库存，再删订单及其关联数据。
     *
     * <p>P3-4：会员存在性判断跨库了——{@code ums_member} 现在在 {@code mall_user} schema 里，
     * 用全限定名 {@code mall_user.ums_member} 做子查询（同一个 MySQL 实例，测试账号有跨库读权限）。
     */
    protected void reclaimOrphanOrders() {
        String orphan = "(SELECT order_no FROM ("
                + "  SELECT o.order_no FROM oms_order o"
                + "   WHERE o.order_no NOT LIKE '88%'"
                + "     AND o.create_time > NOW() - INTERVAL 1 DAY"
                + "     AND o.member_id NOT IN (SELECT m.id FROM mall_user.ums_member m)"
                + ") t)";
        // 非终态订单：库存仍是扣减状态 → 按明细回补(终态 3已完成/4已取消/5已关闭/7已退款 无需回补)
        jdbcTemplate.update("UPDATE mall_product.pms_sku s JOIN ("
                + "  SELECT oi.sku_id, SUM(oi.quantity) q FROM oms_order_item oi"
                + "   WHERE oi.order_no IN " + orphan
                + "     AND oi.order_no IN (SELECT order_no FROM oms_order WHERE order_status IN (0,1,2,6))"
                + "   GROUP BY oi.sku_id) x ON x.sku_id = s.id"
                + " SET s.stock = s.stock + x.q");
        jdbcTemplate.update("DELETE FROM oms_refund WHERE order_no IN " + orphan);
        jdbcTemplate.update("DELETE FROM oms_order_item WHERE order_no IN " + orphan);
        jdbcTemplate.update("DELETE FROM oms_payment WHERE order_no IN " + orphan);
        jdbcTemplate.update("DELETE FROM mall_product.pms_sku_stock_log WHERE order_no IN " + orphan);
        jdbcTemplate.update("DELETE FROM oms_order WHERE order_no IN " + orphan);
    }

    /**
     * 删除当前用例自建会员及其关联资产。
     *
     * <p>会员侧的资产里，购物车/地址/收藏/足迹在 {@code mall_user} schema，评价在 {@code mall_review}。
     * <b>券不在这里清</b>：P5 步骤 C 之后券的读写整体在 {@code mall-marketing}，
     * 单体的券表已是冻结的历史副本（没有任何写入方），而**本套件也不再产生券数据**
     * （交易侧的券用例用 {@code @MockitoBean} 替换券契约，不落库）。
     * 会员行本身也删掉——顺序是先资产后会员。
     * 另外清掉购物车领取记录（{@code mall:idem:cart:claim:{memberId}:*}），避免残留影响后续用例。
     *
     * <p><b>P4 批次 3：评价表的清理指向 {@code mall_review.pms_comment}</b>。
     * 评价域（连同它的表）已经搬去 review 服务，本套件不再产生评价数据；
     * 这条 DELETE 保留是为了"跨进程残留"的卫生（单体用例造的会员 id 若被 review 侧用例引用过，
     * 这里顺手清掉），指向新家而不是即将被 {@code db/03-drop-from-mall.sql} 删掉的旧表。
     */
    protected void deleteMemberAssets() {
        for (Long id : createdMemberIds) {
            jdbcTemplate.update("DELETE FROM mall_user.ums_cart_item WHERE member_id = ?", id);
            jdbcTemplate.update("DELETE FROM mall_user.ums_address WHERE member_id = ?", id);
            jdbcTemplate.update("DELETE FROM mall_user.ums_favorite WHERE member_id = ?", id);
            jdbcTemplate.update("DELETE FROM mall_user.ums_footprint WHERE member_id = ?", id);
            jdbcTemplate.update("DELETE FROM mall_review.pms_comment WHERE member_id = ?", id);
            jdbcTemplate.update("DELETE FROM mall_user.ums_member WHERE id = ?", id);
            try {
                redisTemplate.keys("mall:idem:cart:claim:" + id + ":*").forEach(redisTemplate::delete);
            } catch (Exception ignored) {
                // Redis 不可用时无需清理（领取记录本来也没写进去）
            }
        }
        if (createdMemberIds.isEmpty() && username != null) {
            jdbcTemplate.update("DELETE FROM mall_user.ums_member WHERE username = ?", username);
        }
    }
}
