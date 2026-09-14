package com.mall.gateway.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 网关会员身份过滤器的单元测试（P3-2 的核心防线）。
 *
 * <p>这里不启动整个网关（不需要 Nacos/下游实例），只对过滤器本身做断言——
 * 它守的是**安全边界**，所以用例都围绕"什么情况下身份可信"：
 * <ol>
 *   <li>合法令牌 → 注入三件套（凭据 + memberId + ver）；</li>
 *   <li><b>客户端伪造的 {@code X-Member-Id} 必须被剥离</b>（最关键的一条：不剥离就是任意越权）；</li>
 *   <li>令牌版本不一致 → 401（登出/改密/被禁用后旧 token 立即失效的机制）；</li>
 *   <li>非法/过期令牌 → 401，文案与单体逐字一致；</li>
 *   <li>管理端令牌（typ=admin）不归本过滤器管，原样透传（否则管理端全挂）；</li>
 *   <li>匿名请求放行（公开接口允许游客，由下游决定是否要求登录）；</li>
 *   <li>Redis 故障 → fail-open（版本读不到不把全体用户踢下线，与单体口径一致）。</li>
 * </ol>
 */
class MemberIdentityFilterTest {

    private static final String SECRET = "mall-dev-only-secret-0123456789abcdef-change-me";
    private static final String GATEWAY_TOKEN = "test-gateway-token";

    private final GatewayJwtUtil jwtUtil = new GatewayJwtUtil(SECRET);

    @SuppressWarnings("unchecked")
    private ReactiveStringRedisTemplate redisReturning(String value) {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(value == null ? Mono.empty() : Mono.just(value));
        return template;
    }

    @SuppressWarnings("unchecked")
    private ReactiveStringRedisTemplate redisFailing() {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(Mono.error(new IllegalStateException("redis down")));
        return template;
    }

    private MemberIdentityFilter filter(ReactiveStringRedisTemplate redis) {
        return new MemberIdentityFilter(jwtUtil, redis, GATEWAY_TOKEN, true);
    }

    /**
     * 运行过滤器，返回"实际被传给下游的 exchange"；若过滤器直接拒绝（没调用 chain），
     * 则返回原始 exchange（此时断言它上面的响应体）。
     */
    private ServerWebExchange run(MemberIdentityFilter f, MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwarded.set(ex);
            return Mono.empty();
        };
        f.filter(exchange, chain).block();
        return forwarded.get() != null ? forwarded.get() : exchange;
    }

    /** 独立实现的签发（同一算法、代码独立）：验签测试只有配上独立实现的签发才算真的验证 */
    private static String token(long memberId, String type, long ver) {
        return token(memberId, type, ver, 3600, SECRET);
    }

    private static String token(long memberId, String type, long ver, long ttlSeconds, String secret) {
        long now = Instant.now().getEpochSecond();
        String payload = "{\"sub\":" + memberId + ",\"name\":\"u" + memberId + "\",\"typ\":\"" + type
                + "\",\"ver\":" + ver + ",\"iat\":" + now + ",\"exp\":" + (now + ttlSeconds) + "}";
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String body = b64(payload);
        return header + "." + body + "." + hmac(header + "." + body, secret);
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmac(String input, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 过滤器直接拒绝时断言原 exchange 的响应体（响应是原对象上的，装饰器只是包装请求） */
    private String rejectBody(ReactiveStringRedisTemplate redis, MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter(redis).filter(exchange, e -> Mono.empty()).block();
        return exchange.getResponse().getBodyAsString().block();
    }

    @Test
    @DisplayName("[网关] 合法会员令牌 → 注入身份三件套（凭据/memberId/ver）")
    void injectsIdentity() {
        String token = token(7L, GatewayJwtUtil.TYPE_USER, 0L);
        ServerWebExchange forwarded = run(filter(redisReturning("0")),
                MockServerHttpRequest.get("/api/cart").header("Authorization", "Bearer " + token).build());

        assertThat(forwarded.getRequest().getHeaders().getFirst(MemberIdentityFilter.HEADER_GATEWAY_AUTH))
                .isEqualTo(GATEWAY_TOKEN);
        assertThat(forwarded.getRequest().getHeaders().getFirst(MemberIdentityFilter.HEADER_MEMBER_ID))
                .isEqualTo("7");
        assertThat(forwarded.getRequest().getHeaders().getFirst(MemberIdentityFilter.HEADER_MEMBER_VER))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("[网关][安全] 客户端伪造的 X-Member-Id 被剥离，且伪造头不能替代真身份")
    void stripsForgedHeaders() {
        // ① 匿名 + 伪造身份头 → 头被剥离，且不注入任何身份
        ServerWebExchange anonymous = run(filter(redisReturning(null)),
                MockServerHttpRequest.get("/api/cart")
                        .header(MemberIdentityFilter.HEADER_GATEWAY_AUTH, "forged")
                        .header(MemberIdentityFilter.HEADER_MEMBER_ID, "1")
                        .build());
        assertThat(anonymous.getRequest().getHeaders().getFirst(MemberIdentityFilter.HEADER_MEMBER_ID))
                .as("客户端手写的 X-Member-Id 必须被剥离").isNull();
        assertThat(anonymous.getRequest().getHeaders().getFirst(MemberIdentityFilter.HEADER_GATEWAY_AUTH))
                .as("客户端手写的网关凭据必须被剥离").isNull();

        // ② 合法令牌 + 伪造身份头 → 以令牌解析出的身份为准（覆盖伪造值）
        String token = token(7L, GatewayJwtUtil.TYPE_USER, 0L);
        ServerWebExchange withToken = run(filter(redisReturning("0")),
                MockServerHttpRequest.get("/api/cart")
                        .header("Authorization", "Bearer " + token)
                        .header(MemberIdentityFilter.HEADER_MEMBER_ID, "1")
                        .build());
        assertThat(withToken.getRequest().getHeaders().getFirst(MemberIdentityFilter.HEADER_MEMBER_ID))
                .isEqualTo("7");
    }

    @Test
    @DisplayName("[网关] 令牌版本不一致 → 401「登录已失效，请重新登录」（旧 token 立即失效）")
    void rejectsStaleTokenVersion() {
        // token 里 ver=0，Redis 里当前版本已是 1（登出/改密/被禁用过）
        String token = token(7L, GatewayJwtUtil.TYPE_USER, 0L);
        assertThat(rejectBody(redisReturning("1"), MockServerHttpRequest.get("/api/cart").header("Authorization", "Bearer " + token).build()))
                .isEqualTo("{\"code\":401,\"message\":\"登录已失效，请重新登录\",\"data\":null}");
    }

    @Test
    @DisplayName("[网关] 版本键不存在 = 版本 0：ver=0 的旧 token 仍可用（与单体 current() 语义一致）")
    void treatsMissingVersionKeyAsZero() {
        ServerWebExchange forwarded = run(filter(redisReturning(null)),
                MockServerHttpRequest.get("/api/cart")
                        .header("Authorization", "Bearer " + token(7L, GatewayJwtUtil.TYPE_USER, 0L)).build());
        assertThat(forwarded.getRequest().getHeaders().getFirst(MemberIdentityFilter.HEADER_MEMBER_ID))
                .isEqualTo("7");
    }

    @Test
    @DisplayName("[网关] 非法/过期令牌 → 401，文案与单体逐字一致")
    void rejectsInvalidToken() {
        String expired = token(7L, GatewayJwtUtil.TYPE_USER, 0L, -60, SECRET);            // 已过期
        String wrongSecret = token(7L, GatewayJwtUtil.TYPE_USER, 0L, 3600, "another-secret-key-0123456789abcdef"); // 别的密钥签的
        String tampered = token(7L, GatewayJwtUtil.TYPE_USER, 0L) + "x";                  // 签名被改
        for (String bad : new String[]{"not-a-jwt", "a.b.c", expired, wrongSecret, tampered}) {
            assertThat(rejectBody(redisReturning("0"), MockServerHttpRequest.get("/api/cart").header("Authorization", "Bearer " + bad).build()))
                    .as("非法令牌: " + bad)
                    .isEqualTo("{\"code\":401,\"message\":\"登录已失效，请重新登录\",\"data\":null}");
        }
    }

    @Test
    @DisplayName("[网关] 管理端令牌（typ=admin）原样透传：管理端登录态仍由单体负责")
    void passesAdminTokenThrough() {
        ServerWebExchange forwarded = run(filter(redisReturning("0")),
                MockServerHttpRequest.get("/api/admin/banner")
                        .header("Authorization", "Bearer " + token(1L, GatewayJwtUtil.TYPE_ADMIN, 0L)).build());

        assertThat(forwarded.getRequest().getHeaders().getFirst(MemberIdentityFilter.HEADER_MEMBER_ID))
                .as("管理端令牌不该被当作会员身份注入").isNull();
        assertThat(forwarded.getRequest().getHeaders().getFirst("Authorization")).isNotNull();
    }

    @Test
    @DisplayName("[网关] 匿名请求放行（公开接口允许游客）")
    void allowsAnonymous() {
        ServerWebExchange forwarded = run(filter(redisReturning("0")),
                MockServerHttpRequest.get("/api/product/page").build());
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getRequest().getHeaders().getFirst(MemberIdentityFilter.HEADER_MEMBER_ID)).isNull();
    }

    @Test
    @DisplayName("[网关] Redis 故障 → fail-open 放行（不因缓存故障把全体用户踢下线）")
    void failsOpenWhenRedisDown() {
        ServerWebExchange forwarded = run(filter(redisFailing()),
                MockServerHttpRequest.get("/api/cart")
                        .header("Authorization", "Bearer " + token(7L, GatewayJwtUtil.TYPE_USER, 0L)).build());
        assertThat(forwarded.getRequest().getHeaders().getFirst(MemberIdentityFilter.HEADER_MEMBER_ID))
                .isEqualTo("7");
    }

    @Test
    @DisplayName("[网关] enabled=false 一键回退：不验证、不注入，只剥离伪造头")
    void disabledModeStripsOnly() {
        MemberIdentityFilter off = new MemberIdentityFilter(jwtUtil, redisReturning("0"), GATEWAY_TOKEN, false);
        ServerWebExchange forwarded = run(off, MockServerHttpRequest.get("/api/cart")
                .header("Authorization", "Bearer " + token(7L, GatewayJwtUtil.TYPE_USER, 0L))
                .header(MemberIdentityFilter.HEADER_MEMBER_ID, "1")
                .build());
        assertThat(forwarded.getRequest().getHeaders().getFirst(MemberIdentityFilter.HEADER_MEMBER_ID)).isNull();
        assertThat(forwarded.getRequest().getHeaders().getFirst("Authorization")).isNotNull();
    }
}
