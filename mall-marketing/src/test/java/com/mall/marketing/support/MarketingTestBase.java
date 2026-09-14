package com.mall.marketing.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * mall-marketing 的真库测试基类（与 user-center/content/review 的薄基类同一套思路）。
 *
 * <h2>为什么必须连真库</h2>
 * 本批的**全部正确性**都押在三条条件 UPDATE 的影响行数上（{@code CouponMemberMapper}）：
 * 谁抢到了锁、幂等命中的是"同单"还是"别人"、{@code order_no} 有没有被清空——
 * 任何 mock/fake DB 都证明不了这些。所以这里连的是真的 {@code mall_marketing}，
 * 且**不用** {@code @Transactional} 回滚测试事务：并发用例要求 UPDATE 真的落在别的连接上，
 * "同一个未提交事务里自说自话"证明不了并发语义。代价是必须自己清理（见 {@link #cleanUp()}）。
 *
 * <h2>两个令牌为什么必须写在 @SpringBootTest(properties=...) 上</h2>
 * profile 专属配置（{@code application-dev.yaml}）优先级高于
 * {@code src/test/resources/application.properties}，写进 properties 文件会被 dev 令牌盖掉，
 * 而"无令牌 → 403"的用例照样通过、把问题掩盖（P1/P2/P3/P4 各踩过一次）。
 * 这里的断言必须在**假密钥**下成立，才能证明"鉴权真的生效了"而不是"恰好两边一样"。
 *
 * <h2>测试数据怎么隔离</h2>
 * 用**显式 id 命名空间**（见 {@link #TEST_TEMPLATE_ID_BASE} 等三个基数）+ 独立 memberId：
 *  · 库里 {@code sms_coupon} 只有 2 行（真实模板 id 是 114/115）、{@code sms_coupon_member} 645 行
 *    （真实会员 id 在 800 以内），所以 9_1/9_2/9_3 开头的 id 永远不可能撞上真实数据；
 *  · 每个用例用 {@link #nextSeq()} 取一个新号段，用完在 {@link #cleanUp()} 里按记录的 id 删干净；
 *  · 断言一律带上自己的 memberId（{@code usableCoupons} 是按会员查的），
 *    因此真实数据的存在**不会**让断言变松或变紧。
 */
@SpringBootTest(properties = {
        "mall.internal.token=test-internal-token",
        "mall.gateway.auth-token=test-gateway-token"
})
@AutoConfigureMockMvc
public abstract class MarketingTestBase {

    protected static final String TOKEN_HEADER = "X-Internal-Token";
    protected static final String TOKEN = "test-internal-token";

    /** 网关注入的身份头（本批的 /internal 端点不读它们，夹具留给批次 2 的公开端点） */
    protected static final String GW_AUTH_HEADER = "X-Gateway-Auth";
    protected static final String GW_MEMBER_ID_HEADER = "X-Member-Id";
    protected static final String GW_MEMBER_VER_HEADER = "X-Member-Ver";
    protected static final String GW_SECRET = "test-gateway-token";

    /** 测试数据 id 命名空间（见类注释：远离真实数据） */
    protected static final long TEST_TEMPLATE_ID_BASE = 9_100_000_000L;
    protected static final long TEST_COUPON_ID_BASE = 9_200_000_000L;
    protected static final long TEST_MEMBER_ID_BASE = 9_300_000_000L;

    /** 跨用例/跨测试类共享的号段计数器（JUnit 每个用例新建实例，字段计数器会撞号） */
    private static final AtomicLong SEQ = new AtomicLong(1);

    @Autowired
    protected MockMvc mockMvc;

    /** 指向本服务自己的库（mall_marketing）——"数据是不是真的搬过来了"就靠它断言 */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** Redis 直连（**仅供限流用例断言计数键/清理**；业务代码只经 support/CacheService） */
    @Autowired
    protected StringRedisTemplate redisTemplate;

    /** 本用例的会员 id（每个 @Test 一个新号） */
    protected long memberId;

    private final List<Long> createdTemplates = new ArrayList<>();
    private final List<Long> createdCouponMembers = new ArrayList<>();

    /**
     * 本用例"碰过的会员 id"（默认只有 {@link #memberId}；一个用例里还给**别的会员**发过券时必须
     * 显式 {@link #trackMember(long)}）。清理按它删除 {@code coupon_member} 行——见 {@link #cleanUp()}。
     */
    private final List<Long> touchedMemberIds = new ArrayList<>();

    protected static long nextSeq() {
        return SEQ.getAndIncrement();
    }

    /** 每个用例一套新的 id 号段（不用事务回滚，就必须靠"命名空间"隔离） */
    @BeforeEach
    protected void newNamespace() {
        memberId = TEST_MEMBER_ID_BASE + nextSeq();
        touchedMemberIds.add(memberId);
    }

    /**
     * 登记"本用例还给这个会员发过券"（如限流用例为了拿一个独立计数桶而用了第二个会员）。
     * 不登记 = 那个会员的 {@code coupon_member} 行不会被清理，下一轮运行会撞上它（见 {@link #cleanUp()}）。
     */
    protected void trackMember(long otherMemberId) {
        touchedMemberIds.add(otherMemberId);
    }

    @AfterEach
    protected void cleanUp() {
        for (Long id : createdCouponMembers) {
            jdbcTemplate.update("DELETE FROM mall_marketing.sms_coupon_member WHERE id = ?", id);
        }
        // ⚠️ 还必须按 **member_id** 再删一次：`receive` 端点自己 INSERT 的行**不在** createdCouponMembers 里
        // （那是夹具登记的清单）。漏掉这一条的后果实测过两次：
        //   ① 一次失败的运行留下 (member_id, template_id) = (93…N, 91…N) 的行，而下一轮运行由于
        //      SEQ 从 1 重新开始会拿到**完全相同的 id** → receive 直接 409「已达每人限领数量」；
        //   ② 用例还给**第二个会员**发过券（限流用独立计数桶）时，按当前 memberId 删是删不掉的，
        //      于是留下一条孤儿行（实测残留 id 960000000016）。
        // 所以：按 touchedMemberIds 全删（而不是只删当前 memberId）。
        for (Long id : touchedMemberIds) {
            jdbcTemplate.update("DELETE FROM mall_marketing.sms_coupon_member WHERE member_id = ?", id);
        }
        for (Long id : createdTemplates) {
            jdbcTemplate.update("DELETE FROM mall_marketing.sms_coupon WHERE id = ?", id);
        }
        createdCouponMembers.clear();
        createdTemplates.clear();
        for (Long id : touchedMemberIds) {
            deleteCouponReceiveCounterOf(id);
        }
        touchedMemberIds.clear();
    }

    // ==================================================================
    // 造数据（全部走显式 id，见类注释）
    // ==================================================================

    /** 启用、无门槛、无有效期窗口（valid_type 默认 1 且起止为空 → 任何时间都在窗内） */
    protected long newTemplate(long discountAmount) {
        return newTemplate(discountAmount, 0L, 0, 1, null, null);
    }

    /** 启用 + 指定门槛 */
    protected long newTemplateWithThreshold(long thresholdAmount, long discountAmount) {
        return newTemplate(discountAmount, thresholdAmount, 0, 1, null, null);
    }

    /** 停用（status=1） */
    protected long newDisabledTemplate(long discountAmount) {
        return newTemplate(discountAmount, 0L, 1, 1, null, null);
    }

    /** 固定有效期但**已经过期**（validType=1 + validEndTime 在过去） */
    protected long newWindowExpiredTemplate(long discountAmount) {
        return newTemplate(discountAmount, 0L, 0, 1,
                LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(1));
    }

    /**
     * <b>"能真正领出券来"的模板</b>（P5 批次 2 的领券用例专用）：validType=1 且**起止都给了**
     * ——这与真实种子数据（模板 1/2：{@code 2026-01-01 ~ 2030-12-31}）形状一致。
     *
     * <p>⚠️ 为什么不能拿 {@link #newTemplate(long)} 去领券：那是 validType=1 + 起止全 NULL 的
     * "任何时间都在窗内"夹具，而 {@code CouponMemberServiceImpl.expireOf} 对 validType=1 取的是
     * {@code sms_coupon.valid_end_time} —— 它是 NULL 时，{@code sms_coupon_member.expire_time}
     * 这个 <b>NOT NULL</b> 列写 NULL，MySQL 严格模式直接报 1406 → 对外 500「系统繁忙」。
     * 这是**单体的既有行为**（同一份 {@code expireOf}，一字未改地搬过来），
     * 只是真实数据里"固定时间段"的模板必然有结束时间，所以在旧套件里没被撞到过。
     * 本夹具用真实形状绕开它；"模板 end 为 NULL 时领券 500"这个既有边界已记入批次 2 汇报。
     */
    protected long newReceivableTemplate(long discountAmount) {
        return newTemplate(discountAmount, 0L, 0, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
    }

    /**
     * 造一张券模板。
     *
     * @param status     0 启用 / 1 停用
     * @param validType  1 固定时间段 / 2 领取后 N 天
     */
    protected long newTemplate(long discountAmount, long thresholdAmount, int status, int validType,
                               LocalDateTime validStart, LocalDateTime validEnd) {
        return newTemplate(discountAmount, thresholdAmount, status, validType, validStart, validEnd, null, 1, 0);
    }

    /**
     * 全字段版本（P5 批次 2 的领券用例需要）：
     * {@code totalCount}（NULL=不限量，用来造"券已被领完"）、{@code perMemberLimit}、
     * {@code receivedCount}（已发量，用来造"已达每人限领数量"）。
     */
    protected long newTemplate(long discountAmount, long thresholdAmount, int status, int validType,
                               LocalDateTime validStart, LocalDateTime validEnd,
                               Integer totalCount, int perMemberLimit, int receivedCount) {
        long id = TEST_TEMPLATE_ID_BASE + nextSeq();
        jdbcTemplate.update("""
                INSERT INTO mall_marketing.sms_coupon
                    (id, name, type, threshold_amount, discount_amount, total_count, received_count,
                     per_member_limit, valid_type, valid_start_time, valid_end_time, valid_days,
                     status, create_time, update_time)
                VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, 7, ?, NOW(), NOW())
                """, id, "测试券-" + id, thresholdAmount, discountAmount, totalCount, receivedCount,
                perMemberLimit, validType, validStart, validEnd, status);
        createdTemplates.add(id);
        return id;
    }

    /**
     * 登记一个"由被测代码自己建出来的"模板 id（如后台 create 端点建的券）。
     * 不登记 = 用例结束后那行会留在库里（下一轮撞 id 的坑见 {@link #cleanUp()}）。
     */
    protected void trackTemplate(long templateId) {
        createdTemplates.add(templateId);
    }

    /** 券模板的当前状态（领券/停用类断言用） */
    protected int templateStatusOf(long templateId) {
        Integer s = intOf("SELECT status FROM mall_marketing.sms_coupon WHERE id = ?", templateId);
        return s == null ? -999 : s;
    }

    /** 券模板的已发量（"领取必须 +1"就靠它断言） */
    protected int receivedCountOf(long templateId) {
        Integer n = intOf("SELECT received_count FROM mall_marketing.sms_coupon WHERE id = ?", templateId);
        return n == null ? -1 : n;
    }

    /** 未使用、未过期（expireTime = now + 7 天）、无 order_no */
    protected long newCouponMember(long templateId) {
        return newCouponMember(templateId, memberId, 0, LocalDateTime.now().plusDays(7), null, null);
    }

    /** 造一张用户券（显式 id） */
    protected long newCouponMember(long templateId, long ownerMemberId, int couponStatus,
                                  LocalDateTime expireTime, String orderNo, LocalDateTime useTime) {
        long id = TEST_COUPON_ID_BASE + nextSeq();
        jdbcTemplate.update("""
                INSERT INTO mall_marketing.sms_coupon_member
                    (id, template_id, member_id, coupon_status, receive_time, expire_time, order_no, use_time)
                VALUES (?, ?, ?, ?, NOW(), ?, ?, ?)
                """, id, templateId, ownerMemberId, couponStatus, expireTime, orderNo, useTime);
        createdCouponMembers.add(id);
        return id;
    }

    // ==================================================================
    // 读断言辅助
    // ==================================================================

    /** 当前连接的库名；"配置写对了但 profile 没生效"是最容易犯的错，所以直接问数据库 */
    protected String schemaName() {
        return jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
    }

    protected long countOf(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    protected Long longOf(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    protected Integer intOf(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    protected String stringOf(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    protected LocalDateTime dateTimeOf(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, LocalDateTime.class, args);
    }

    /** 库里这张券的 coupon_status（三态真值，不是对外投影值） */
    protected int dbStatusOf(long couponMemberId) {
        Integer s = intOf("SELECT coupon_status FROM mall_marketing.sms_coupon_member WHERE id = ?", couponMemberId);
        return s == null ? -999 : s;
    }

    /** 库里这张券的 order_no（可为 null） */
    protected String dbOrderNoOf(long couponMemberId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_no FROM mall_marketing.sms_coupon_member WHERE id = ?", String.class, couponMemberId);
    }

    protected String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    // ==================================================================
    // 网关身份 / Redis（P5 批次 2：会员侧公开端点用）
    // ==================================================================

    /**
     * 给请求装上"网关注入的身份头"（{@code X-Gateway-Auth} + {@code X-Member-Id}）。
     *
     * <p>这两个头**就是**生产链路里网关验签后注入的东西，因此测试不需要真的起网关，
     * 也不需要 JWT：会员侧端点（{@code /api/coupon/**}）的身份链路断言只依赖本方法的两个头
     * （值取自 {@code mall.gateway.auth-token} 的测试值）。
     * 反过来，不调本方法就等于**匿名**——按 {@code GatewayIdentityResolver} 的 fail-closed 口径
     * 应当得到 401「未登录」。
     */
    protected MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder builder, long memberId) {
        return builder.header(GW_AUTH_HEADER, GW_SECRET)
                .header(GW_MEMBER_ID_HEADER, String.valueOf(memberId))
                .header(GW_MEMBER_VER_HEADER, "1");
    }

    /**
     * Redis 是否可用：限流计数类用例统一用 {@code assumeTrue(redisUp())} 守卫
     * （本机没起 Redis 时按"跳过"而不是"失败"——因为限流是 **fail-open** 的，
     * 没有 Redis 时"计数不生效"本来就是正确行为，不是缺陷）。
     */
    protected boolean redisUp() {
        try {
            redisTemplate.opsForValue().set("mall:test:ping", "pong", Duration.ofSeconds(10));
            return "pong".equals(redisTemplate.opsForValue().get("mall:test:ping"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 清理**本用例自己造的**限流计数键：{@code mall:rl:coupon_receive:u{memberId}}。
     *
     * <p>⚠️ 刻意**只删自己这一把键**，不做 {@code mall:rl:*} 通配删除：
     * 这个 Redis 是全站共用的（网关的 {@code mall:rl:gw:*}、user-center 的登录/注册计数都在里面），
     * 一个测试套件清空整个 {@code mall:rl:*} 命名空间 = 顺手把别人的限流计数清零。
     */
    protected void deleteCouponReceiveCounter() {
        deleteCouponReceiveCounterOf(memberId);
    }

    private void deleteCouponReceiveCounterOf(long ownerMemberId) {
        try {
            redisTemplate.delete("mall:rl:coupon_receive:u" + ownerMemberId);
        } catch (Exception ignored) {
            // Redis 不可用时无需清理（计数本来也没写进去）
        }
    }
}
