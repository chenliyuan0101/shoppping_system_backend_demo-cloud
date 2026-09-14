package com.mall.usercenter.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;

/**
 * mall-user-center 的真库测试基类（与 content 的薄基类同一套思路）。
 *
 * <p>⚠️ 令牌必须写在 {@code @SpringBootTest(properties=...)} 上：profile 专属配置
 * （{@code application-dev.yaml}）优先级高于 {@code src/test/resources/application.properties}，
 * 写进 properties 文件会被 dev 令牌盖掉，而"无令牌 → 403"的用例照样通过、把问题掩盖（P2 实测踩过）。
 *
 * <p>同理，P3 的网关身份凭据也在这里注入固定值 {@code test-gateway-token}：测试里手工构造
 * {@code X-Gateway-Auth} 就等于"网关验签后注入的身份"，身份链路的断言因此不依赖本机网关是否在跑。
 */
@SpringBootTest(properties = {
        "mall.internal.token=test-internal-token",
        "mall.gateway.auth-token=test-gateway-token"
})
@AutoConfigureMockMvc
public abstract class UserCenterTestBase {

    protected static final String TOKEN_HEADER = "X-Internal-Token";
    protected static final String TOKEN = "test-internal-token";

    /** 网关注入的三个身份头（值必须与上面注入的 {@code mall.gateway.auth-token} 对应） */
    protected static final String GW_AUTH_HEADER = "X-Gateway-Auth";
    protected static final String GW_MEMBER_ID_HEADER = "X-Member-Id";
    protected static final String GW_MEMBER_VER_HEADER = "X-Member-Ver";
    protected static final String GW_SECRET = "test-gateway-token";

    @Autowired
    protected MockMvc mockMvc;

    /** 指向本服务自己的库（mall_user）——"数据是不是真的搬过来了"就靠它断言 */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    protected boolean redisUp() {
        try {
            redisTemplate.opsForValue().set("mall:test:ping", "pong", Duration.ofSeconds(10));
            return "pong".equals(redisTemplate.opsForValue().get("mall:test:ping"));
        } catch (Exception e) {
            return false;
        }
    }

    protected long currentSchema() {
        Long n = jdbcTemplate.queryForObject("SELECT DATABASE() IS NOT NULL", Long.class);
        return n == null ? 0 : n;
    }

    protected String schemaName() {
        return jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
    }

    protected String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    // ==================== P3-3（ums 子批）：真库用例的公共夹具 ====================

    /**
     * 给请求装上"网关注入的身份头"（{@code X-Gateway-Auth} + {@code X-Member-Id}）。
     *
     * <p>这两个头**就是**生产链路里网关验签后注入的东西，因此测试不需要真的起网关，
     * 也不需要 JWT：身份链路的断言只依赖本方法的两个头（值取自 {@code mall.gateway.auth-token}）。
     * 反过来，不调本方法就等于"未登录"（401），这正是身份模型变了的可执行证据。
     */
    protected MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder builder, long memberId) {
        return builder.header(GW_AUTH_HEADER, GW_SECRET)
                .header(GW_MEMBER_ID_HEADER, String.valueOf(memberId));
    }

    /**
     * 真库插入一个会员（用户名带随机后缀，避免与 seed 冲突），返回其 id。
     *
     * <p>网关身份只认头、不查库，但真库用例里"会员确实存在"更贴近生产
     * （会员档案类的断言、禁用→令牌版本这类链路都必须落在真实行上）。
     * 用完请调用 {@link #deleteMember(long)}（连同该会员的业务数据）。
     */
    protected long insertMember(String usernamePrefix) {
        String username = usernamePrefix + (System.nanoTime() % 100_000_000L);
        jdbcTemplate.update("""
                INSERT INTO ums_member (username, password, nickname, status, deleted)
                VALUES (?, ?, ?, 1, 0)
                """, username, "{noop}test-only", "集成测试会员");
        Long id = jdbcTemplate.queryForObject("SELECT id FROM ums_member WHERE username = ?", Long.class, username);
        if (id == null) {
            throw new IllegalStateException("插入会员失败: " + username);
        }
        return id;
    }

    /** 物理删除测试会员及其业务数据（可重复执行；不留残余以免污染其它套件） */
    protected void deleteMember(long memberId) {
        jdbcTemplate.update("DELETE FROM ums_cart_item WHERE member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM ums_address WHERE member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM ums_favorite WHERE member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM ums_footprint WHERE member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM ums_notification WHERE member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM ums_member WHERE id = ?", memberId);
        redisTemplate.delete(CacheKeys.tokenVersion(TokenVersionService.TYPE_USER, memberId));
        redisTemplate.delete(CacheKeys.memberStatus(memberId));
    }

    /** 购物车里的行数（结算闸门"删没删干净"的证据） */
    protected long cartRows(long memberId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ums_cart_item WHERE member_id = ?", Long.class, memberId);
        return n == null ? 0L : n;
    }

    /** 直接落一条购物车明细（结算闸门的用例只关心闸门本身，不需要商品域参与） */
    protected long insertCartItem(long memberId, long spuId, long skuId, int quantity, int checked) {
        jdbcTemplate.update("""
                INSERT INTO ums_cart_item (member_id, spu_id, sku_id, quantity, checked)
                VALUES (?, ?, ?, ?, ?)
                """, memberId, spuId, skuId, quantity, checked);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM ums_cart_item WHERE member_id = ? AND sku_id = ?", Long.class, memberId, skuId);
        if (id == null) {
            throw new IllegalStateException("插入购物车明细失败: memberId=" + memberId + " skuId=" + skuId);
        }
        return id;
    }
}
