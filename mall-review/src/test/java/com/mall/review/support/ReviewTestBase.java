package com.mall.review.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import com.mall.common.support.MemberId;

/**
 * mall-review 的真库测试基类（与 user-center/content 的薄基类同一套思路）。
 *
 * <p>⚠️ 两个令牌必须写在 {@code @SpringBootTest(properties=...)} 上：profile 专属配置
 * （{@code application-dev.yaml}）优先级高于 {@code src/test/resources/application.properties}，
 * 写进 properties 文件会被 dev 令牌盖掉，而"无令牌 → 403"的用例照样通过、把问题掩盖
 * （P2/P3 各踩过一次，见两个服务里同一段注释）。这里的断言必须在**假密钥**下成立，
 * 才能证明"鉴权真的生效了"而不是"恰好两边一样"。
 *
 * <p>网关身份凭据同理注入固定值 {@code test-gateway-token}：测试里手工构造
 * {@code X-Gateway-Auth} 就等于"网关验签后注入的身份"，身份链路的断言因此不依赖本机网关是否在跑。
 */
@SpringBootTest(properties = {
        "mall.internal.token=test-internal-token",
        "mall.gateway.auth-token=test-gateway-token",
        // =====================================================================
        // P8-4：运行态已换成**最小权限账号** `mall_review`（只对 mall_review.* 有权限），
        //   但真库套件必须**跨库**：BackfillPendingSqlTest 要读 `mall_trade.oms_order_item`
        //   才能证明"只对已完成的订单项回填"（实测：不覆盖就 3 个用例报 ERROR 1142）。
        //   ⇒ 测试期显式覆盖回 root。**必须写在这里**（`@SpringBootTest(properties)` 优先级最高）：
        //     profile 专属文件 application-dev.yaml 会盖掉 test classpath 的 application.properties
        //     （mall-trade 踩过：45 个用例报并包装成 `BadSqlGrammar`，极易误判成 SQL 写错）。
        // =====================================================================
        "spring.datasource.username=root",
        "spring.datasource.password=123456"
})
@AutoConfigureMockMvc
public abstract class ReviewTestBase {

    protected static final String TOKEN_HEADER = "X-Internal-Token";
    protected static final String TOKEN = "test-internal-token";

    /** 网关注入的身份头（值必须与上面注入的 {@code mall.gateway.auth-token} 对应） */
    protected static final String GW_AUTH_HEADER = "X-Gateway-Auth";
    protected static final String GW_MEMBER_ID_HEADER = "X-Member-Id";
    protected static final String GW_MEMBER_VER_HEADER = "X-Member-Ver";
    protected static final String GW_SECRET = "test-gateway-token";

    @Autowired
    protected MockMvc mockMvc;

    /** 指向本服务自己的库（mall_review）——"数据是不是真的搬过来了"就靠它断言 */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

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

    protected String stringOf(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    protected boolean redisUp() {
        try {
            redisTemplate.opsForValue().set("mall:test:ping", "pong", Duration.ofSeconds(10));
            return "pong".equals(redisTemplate.opsForValue().get("mall:test:ping"));
        } catch (Exception e) {
            return false;
        }
    }

    protected String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    /**
     * 给请求装上"网关注入的身份头"（{@code X-Gateway-Auth} + {@code X-Member-Id}）。
     *
     * <p>这两个头**就是**生产链路里网关验签后注入的东西，因此测试不需要真的起网关，
     * 也不需要 JWT：身份链路的断言只依赖本方法的两个头（值取自 {@code mall.gateway.auth-token}）。
     * 反过来，不调本方法就等于"未登录"（401）。
     *
     * <p>P4 第 1 批还没有用到 {@code @MemberId} 的接口，这个夹具是给下一批
     * （提交/删除评价的归属校验）准备的，同时它让"身份模型变了"这件事在测试里可执行。
     */
    protected MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder builder, long memberId) {
        return builder.header(GW_AUTH_HEADER, GW_SECRET)
                .header(GW_MEMBER_ID_HEADER, String.valueOf(memberId));
    }
}
