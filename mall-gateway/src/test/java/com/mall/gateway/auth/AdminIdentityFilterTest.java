package com.mall.gateway.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 网关管理端身份过滤器的单元测试（P7 §2/§2.5 的核心防线，与 {@link MemberIdentityFilterTest} 同形）。
 *
 * <p>不启动整个网关（不需要 Nacos/下游实例），只对过滤器本身断言——它守的是<b>安全边界</b>
 * 与<b>文案契约</b>，所以用例围绕"什么情况下身份可信 / 什么文案由谁产出"：
 * <ol>
 *   <li>合法管理员令牌 → 注入三件套（凭据 + adminId + ver）；</li>
 *   <li><b>客户端伪造的 {@code X-Admin-*}/{@code X-Gateway-Auth} 必须被剥离</b>（不剥离就是任意越权）；</li>
 *   <li>头缺失/格式错 → {@code 401 未登录}；验签/过期/版本 → {@code 401 登录已失效，请重新登录}；</li>
 *   <li>会员令牌（{@code typ=user}）打后台 → {@code 401 登录已失效，请使用管理员账号登录}（逐字）；</li>
 *   <li>状态缓存说"禁用" → {@code 403 账号已被禁用}（逐字）；<b>键缺失/不可解析/Redis 挂 → fail-open 放行</b>
 *       ——今天没有写入方，这条检查必须是"不可见"的；</li>
 *   <li>登录路径放行；会员路径完全不介入（连 Redis 都不读）；</li>
 *   <li>{@code enabled=false} / 凭据未配置 → 只剥离、不验签不注入（管理端回到单体拦截器，可一键回退）。</li>
 * </ol>
 *
 * <p>⚠️ 本测试里的 Redis key 名与请求头名都写成**字面量**（不用被测类的常量）：
 * 它们是要交给 {@code mall-admin} 的**契约**，必须由测试钉死，改常量不该能"悄悄"改契约。
 */
class AdminIdentityFilterTest {

    private static final String SECRET = "mall-dev-only-secret-0123456789abcdef-change-me";
    private static final String GATEWAY_TOKEN = "test-gateway-token";

    private static final long ADMIN_ID = 9L;
    private static final long MEMBER_ID = 7L;

    /** 契约：写入方 mall-admin 必须写同样的两个键 */
    private static final String KEY_STATUS = "mall:cache:admin:status:9";
    private static final String KEY_VER = "mall:token:ver:admin:9";

    private final GatewayJwtUtil jwtUtil = new GatewayJwtUtil(SECRET);

    // ==================== 桩：Redis / 过滤器 / 运行 ====================

    private record RedisStub(ReactiveStringRedisTemplate template, ReactiveValueOperations<String, String> ops) {
    }

    /** 按 key 返回值：未登记的 key = 不存在（Mono.empty） */
    @SuppressWarnings("unchecked")
    private static RedisStub redis(Map<String, String> values) {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(invocation -> {
            String value = values.get(invocation.getArgument(0, String.class));
            return value == null ? Mono.empty() : Mono.just(value);
        });
        return new RedisStub(template, ops);
    }

    /** Redis 整体不可用（两个键的读都异常） */
    @SuppressWarnings("unchecked")
    private static RedisStub redisDown() {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(Mono.error(new IllegalStateException("redis down")));
        return new RedisStub(template, ops);
    }

    /** 只有"令牌版本"这个读失败（状态缓存正常）：证明两个 fail-open 各自独立成立 */
    @SuppressWarnings("unchecked")
    private static RedisStub redisFailingOnVersionOnly() {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            return key.startsWith("mall:cache:admin:status:")
                    ? Mono.just("1")
                    : Mono.error(new IllegalStateException("redis down"));
        });
        return new RedisStub(template, ops);
    }

    private AdminIdentityFilter filter(ReactiveStringRedisTemplate redis) {
        return new AdminIdentityFilter(jwtUtil, redis, GATEWAY_TOKEN, true);
    }

    /** 返回"实际被传给下游的 exchange"；被拒绝（没调用 chain）时返回 {@code null} */
    private static ServerWebExchange forward(AdminIdentityFilter f, MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        f.filter(exchange, ex -> {
            forwarded.set(ex);
            return Mono.empty();
        }).block();
        return forwarded.get();
    }

    /** 断言"被网关直接拒绝"，并返回响应体（拒绝时响应是原 exchange 上的） */
    private static String rejectBody(AdminIdentityFilter f, MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        f.filter(exchange, ex -> {
            forwarded.set(ex);
            return Mono.empty();
        }).block();
        assertThat(forwarded.get()).as("本用例必须被网关直接拒绝（不许放行到下游）").isNull();
        assertThat(exchange.getResponse().getStatusCode()).as("对外契约：业务错误 HTTP 恒 200").isEqualTo(HttpStatus.OK);
        return exchange.getResponse().getBodyAsString().block();
    }

    // ==================== 独立实现的签发（与单体同算法、代码独立） ====================

    private static String adminToken(long ver) {
        return token(ADMIN_ID, GatewayJwtUtil.TYPE_ADMIN, ver);
    }

    private static String memberToken() {
        return token(MEMBER_ID, GatewayJwtUtil.TYPE_USER, 0L);
    }

    private static String token(long userId, String type, long ver) {
        return token(userId, type, ver, 3600, SECRET);
    }

    private static String token(long userId, String type, long ver, long ttlSeconds, String secret) {
        long now = Instant.now().getEpochSecond();
        String payload = "{\"sub\":" + userId + ",\"name\":\"u" + userId + "\",\"typ\":\"" + type
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

    private static MockServerHttpRequest adminRequest(String path, String token) {
        return MockServerHttpRequest.get(path).header("Authorization", "Bearer " + token).build();
    }

    // ==================== ① 注入 ====================

    @Test
    @DisplayName("[网关] 合法管理员令牌 → 注入 X-Gateway-Auth / X-Admin-Id / X-Admin-Ver（头名即契约）")
    void injectsAdminIdentity() {
        RedisStub stub = redis(Map.of());

        ServerWebExchange forwarded = forward(filter(stub.template()), adminRequest("/api/admin/product/page", adminToken(0L)));

        assertThat(forwarded).as("合法管理员令牌必须放行").isNotNull();
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Gateway-Auth")).isEqualTo(GATEWAY_TOKEN);
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Id")).isEqualTo("9");
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Ver")).isEqualTo("0");
        // 契约：读的就是这两个键（mall-admin 将来必须写同样的名字）
        verify(stub.ops()).get(KEY_STATUS);
        verify(stub.ops()).get(KEY_VER);
    }

    @Test
    @DisplayName("[网关] 版本键 = 0（键存在且值为 0）→ ver=0 的令牌仍可用（与单体 current() 语义一致）")
    void acceptsZeroVersionFromRedis() {
        RedisStub stub = redis(Map.of(KEY_VER, "0"));

        ServerWebExchange forwarded = forward(filter(stub.template()), adminRequest("/api/admin/dashboard/summary", adminToken(0L)));

        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Ver")).isEqualTo("0");
    }

    // ==================== ② 伪造头剥离（安全） ====================

    @Test
    @DisplayName("[网关][安全] 客户端伪造的 X-Admin-*/X-Gateway-Auth 被剥离，伪造值不能替代真身份")
    void stripsForgedIdentityHeaders() {
        // ① 合法令牌 + 伪造头 → 以令牌解析结果为准（覆盖伪造值），伪造的凭据被换成网关凭据
        RedisStub stub = redis(Map.of());
        MockServerHttpRequest forged = MockServerHttpRequest.get("/api/admin/product/page")
                .header("Authorization", "Bearer " + adminToken(0L))
                .header("X-Gateway-Auth", "forged-by-client")
                .header("X-Admin-Id", "1")
                .header("X-Admin-Ver", "99")
                .build();

        ServerWebExchange forwarded = forward(filter(stub.template()), forged);

        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Gateway-Auth"))
                .as("客户端手写的凭据必须被剥离后再注入真凭据").isEqualTo(GATEWAY_TOKEN);
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Id"))
                .as("身份以令牌为准，不是客户端手写的 1").isEqualTo("9");
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Ver"))
                .as("版本以令牌为准，不是客户端手写的 99").isEqualTo("0");
    }

    @Test
    @DisplayName("[网关][安全] 匿名请求里的伪造 X-Admin-Id 不会活到下游（且照样 401 未登录）")
    void forgedHeadersDoNotSurviveOnAnonymousRequest() {
        MockServerHttpRequest forged = MockServerHttpRequest.get("/api/admin/product/page")
                .header("X-Gateway-Auth", "forged")
                .header("X-Admin-Id", "1")
                .build();

        assertThat(rejectBody(filter(redis(Map.of()).template()), forged))
                .isEqualTo("{\"code\":401,\"message\":\"未登录\",\"data\":null}");
    }

    // ==================== ③ 头缺失/格式错 → 401 未登录 ====================

    @Test
    @DisplayName("[网关] 缺 Authorization / 不是 Bearer 格式 → 401「未登录」（与单体 AuthHeader 逐字一致）")
    void rejectsMissingOrMalformedAuthorization() {
        String expected = "{\"code\":401,\"message\":\"未登录\",\"data\":null}";
        for (String authorization : new String[]{null, "", "   ", "Token abc", "Bearer", "Bearer ", "Bearer    "}) {
            var builder = MockServerHttpRequest.get("/api/admin/product/page");
            if (authorization != null) {
                builder.header("Authorization", authorization);
            }
            assertThat(rejectBody(filter(redis(Map.of()).template()), builder.build()))
                    .as("Authorization=[%s]", authorization)
                    .isEqualTo(expected);
        }
    }

    // ==================== ④ 验签/过期/版本 → 401 登录已失效，请重新登录 ====================

    @Test
    @DisplayName("[网关] 过期 / 签名不对 / 被篡改 / 结构非法 → 401「登录已失效，请重新登录」")
    void rejectsInvalidToken() {
        String expired = token(ADMIN_ID, GatewayJwtUtil.TYPE_ADMIN, 0L, -60, SECRET);
        String wrongSecret = token(ADMIN_ID, GatewayJwtUtil.TYPE_ADMIN, 0L, 3600, "another-secret-key-0123456789abcdef");
        String tampered = adminToken(0L) + "x";
        String expected = "{\"code\":401,\"message\":\"登录已失效，请重新登录\",\"data\":null}";

        for (String bad : new String[]{"not-a-jwt", "a.b.c", expired, wrongSecret, tampered}) {
            assertThat(rejectBody(filter(redis(Map.of()).template()), adminRequest("/api/admin/product/page", bad)))
                    .as("非法令牌: " + bad)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("[网关] 令牌版本不一致（已登出/改密/被禁用）→ 401「登录已失效，请重新登录」")
    void rejectsStaleTokenVersion() {
        // token 里 ver=0，Redis 里 mall:token:ver:admin:9 已是 1
        RedisStub stub = redis(Map.of(KEY_VER, "1"));

        assertThat(rejectBody(filter(stub.template()), adminRequest("/api/admin/product/page", adminToken(0L))))
                .isEqualTo("{\"code\":401,\"message\":\"登录已失效，请重新登录\",\"data\":null}");
        verify(stub.ops()).get(KEY_VER);
    }

    @Test
    @DisplayName("[网关] 版本键值不是数字 → 按不可判定处理，放行（不误伤）")
    void failsOpenOnUnparsableVersionValue() {
        RedisStub stub = redis(Map.of(KEY_VER, "not-a-number"));

        ServerWebExchange forwarded = forward(filter(stub.template()), adminRequest("/api/admin/product/page", adminToken(0L)));

        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Id")).isEqualTo("9");
    }

    // ==================== ⑤ 双体系隔离：会员令牌不得打后台 ====================

    @Test
    @DisplayName("[网关] 会员令牌（typ=user）打后台 → 401「登录已失效，请使用管理员账号登录」（逐字）")
    void rejectsMemberToken() {
        assertThat(rejectBody(filter(redis(Map.of()).template()), adminRequest("/api/admin/product/page", memberToken())))
                .isEqualTo("{\"code\":401,\"message\":\"登录已失效，请使用管理员账号登录\",\"data\":null}");
    }

    @Test
    @DisplayName("[网关] typ 既不是 user 也不是 admin（含大小写不同的 ADMIN）→ 同一条「请使用管理员账号登录」")
    void rejectsUnknownTokenType() {
        String expected = "{\"code\":401,\"message\":\"登录已失效，请使用管理员账号登录\",\"data\":null}";
        String uppercase = token(ADMIN_ID, "ADMIN", 0L);
        String unknown = token(ADMIN_ID, "manager", 0L);

        for (String bad : new String[]{uppercase, unknown}) {
            assertThat(rejectBody(filter(redis(Map.of()).template()), adminRequest("/api/admin/product/page", bad)))
                    .as("typ 判定与单体 equals 口径一致（大小写敏感）")
                    .isEqualTo(expected);
        }
    }

    // ==================== ⑥ 状态缓存（P7 §2.5）：403 账号已被禁用 ====================

    @Test
    @DisplayName("[网关] 状态缓存说禁用 → 200 + 403「账号已被禁用」（裸数字写法）")
    void rejectsDisabledAdminFromStatusCache() {
        RedisStub stub = redis(Map.of(KEY_STATUS, "0"));

        assertThat(rejectBody(filter(stub.template()), adminRequest("/api/admin/product/page", adminToken(0L))))
                .isEqualTo("{\"code\":403,\"message\":\"账号已被禁用\",\"data\":null}");
        verify(stub.ops()).get(KEY_STATUS);
        verify(stub.ops(), never()).get(KEY_VER);
    }

    @Test
    @DisplayName("[网关] 状态缓存说禁用 → 403（与会员侧同构的 JSON 写法也认）")
    void rejectsDisabledAdminFromJsonStatusCache() {
        RedisStub stub = redis(Map.of(KEY_STATUS, "{\"adminId\":9,\"username\":\"admin\",\"status\":0}"));

        assertThat(rejectBody(filter(stub.template()), adminRequest("/api/admin/member/page", adminToken(0L))))
                .isEqualTo("{\"code\":403,\"message\":\"账号已被禁用\",\"data\":null}");
    }

    @Test
    @DisplayName("[网关] 已禁用 + 版本也不符 → 仍是 403（判定顺序与单体 status 先于 version 一致）")
    void statusCheckRunsBeforeVersionCheck() {
        RedisStub stub = redis(Map.of(KEY_STATUS, "{\"adminId\":9,\"status\":0}", KEY_VER, "99"));

        assertThat(rejectBody(filter(stub.template()), adminRequest("/api/admin/product/page", adminToken(0L))))
                .as("顺序反了就会把 403 变成 401（文案漂移）")
                .isEqualTo("{\"code\":403,\"message\":\"账号已被禁用\",\"data\":null}");
    }

    @Test
    @DisplayName("[网关] 状态缓存存在且为启用(1) → 放行（不影响其它检查）")
    void acceptsEnabledStatusCache() {
        RedisStub stub = redis(Map.of(KEY_STATUS, "{\"adminId\":9,\"username\":\"admin\",\"status\":1}"));

        ServerWebExchange forwarded = forward(filter(stub.template()), adminRequest("/api/admin/product/page", adminToken(0L)));

        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Id")).isEqualTo("9");
    }

    // ==================== ⑦ fail-open：今天这条检查必须是"不可见"的 ====================

    @Test
    @DisplayName("[网关] 状态缓存键不存在（今天没有写入方）→ fail-open 放行，不产生 403")
    void failsOpenWhenStatusCacheAbsent() {
        RedisStub stub = redis(Map.of(KEY_VER, "0"));

        ServerWebExchange forwarded = forward(filter(stub.template()), adminRequest("/api/admin/product/page", adminToken(0L)));

        assertThat(forwarded).as("键还没人写，绝不能因此把管理端挡在门外").isNotNull();
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Id")).isEqualTo("9");
    }

    @Test
    @DisplayName("[网关] 状态缓存值无法解析 → fail-open 放行")
    void failsOpenOnUnparsableStatusValue() {
        RedisStub stub = redis(Map.of(KEY_STATUS, "<html>redis proxy error</html>"));

        ServerWebExchange forwarded = forward(filter(stub.template()), adminRequest("/api/admin/product/page", adminToken(0L)));

        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Id")).isEqualTo("9");
    }

    @Test
    @DisplayName("[网关] Redis 整体不可用 → fail-open 放行（不把全体管理员踢下线）")
    void failsOpenWhenRedisDown() {
        ServerWebExchange forwarded = forward(filter(redisDown().template()), adminRequest("/api/admin/product/page", adminToken(0L)));

        assertThat(forwarded).as("Redis 故障不能变成管理端全站 401").isNotNull();
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Ver")).isEqualTo("0");
    }

    @Test
    @DisplayName("[网关] 只有令牌版本那个读失败 → 同样 fail-open（两个 fail-open 各自独立成立）")
    void failsOpenWhenOnlyVersionReadFails() {
        ServerWebExchange forwarded = forward(filter(redisFailingOnVersionOnly().template()),
                adminRequest("/api/admin/product/page", adminToken(0L)));

        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Id")).isEqualTo("9");
    }

    // ==================== ⑧ 登录路径放行 / 会员路径不介入 ====================

    @Test
    @DisplayName("[网关] /api/admin/auth/login 放行（不带令牌也要能登录）")
    void bypassesLoginPath() {
        RedisStub stub = redis(Map.of());

        ServerWebExchange forwarded = forward(filter(stub.template()),
                MockServerHttpRequest.post("/api/admin/auth/login").build());

        assertThat(forwarded).as("登录端点必须放行（与单体 excludePathPatterns 一致）").isNotNull();
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Id")).isNull();
        verifyNoInteractions(stub.ops());
    }

    @Test
    @DisplayName("[网关] 登录路径上伪造的 X-Admin-Id / X-Gateway-Auth 仍被剥离")
    void stripsForgedHeadersOnLoginPath() {
        RedisStub stub = redis(Map.of());

        ServerWebExchange forwarded = forward(filter(stub.template()),
                MockServerHttpRequest.post("/api/admin/auth/login")
                        .header("X-Gateway-Auth", "forged")
                        .header("X-Admin-Id", "1")
                        .build());

        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Id")).isNull();
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Gateway-Auth")).isNull();
    }

    @Test
    @DisplayName("[网关] 会员路径完全不介入：不拒绝、不注入、连 Redis 都不读")
    void memberPathUntouched() {
        RedisStub stub = redis(Map.of());

        // 模拟"会员过滤器已经注入过身份"的请求（它排在本过滤器之前）
        ServerWebExchange forwarded = forward(filter(stub.template()),
                MockServerHttpRequest.get("/api/cart")
                        .header("Authorization", "Bearer " + memberToken())
                        .header("X-Gateway-Auth", "injected-by-member-filter")
                        .header("X-Member-Id", "7")
                        .header("X-Member-Ver", "0")
                        .build());

        assertThat(forwarded).as("会员路径不能被管理端过滤器拒绝").isNotNull();
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Member-Id")).isEqualTo("7");
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Gateway-Auth"))
                .as("会员过滤器注入的凭据不能被本过滤器删掉（P3 行为必须逐字不变）")
                .isEqualTo("injected-by-member-filter");
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Id")).isNull();
        verifyNoInteractions(stub.ops());
    }

    @Test
    @DisplayName("[网关][安全] 非管理端路径上伪造的 X-Admin-Id 也被剥离（任何路径都不许活下来）")
    void stripsForgedAdminHeadersOnMemberPath() {
        RedisStub stub = redis(Map.of());

        ServerWebExchange forwarded = forward(filter(stub.template()),
                MockServerHttpRequest.get("/api/product/page").header("X-Admin-Id", "1").build());

        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Id")).isNull();
    }

    @Test
    @DisplayName("[网关] 拦截范围与单体 addPathPatterns(\"/api/admin/**\") 同义：/api/admin 在内，/api/administrator 不在内")
    void scopeMatchesMonolithInterceptor() {
        RedisStub stub = redis(Map.of());

        // /api/admin（无尾斜杠）也在 Ant 的 /api/admin/** 覆盖范围内 → 401 未登录
        assertThat(rejectBody(filter(stub.template()), MockServerHttpRequest.get("/api/admin").build()))
                .isEqualTo("{\"code\":401,\"message\":\"未登录\",\"data\":null}");

        // /api/administrator/** 不是同一段路径 → 不介入
        ServerWebExchange forwarded = forward(filter(stub.template()),
                MockServerHttpRequest.get("/api/administrator/x").build());
        assertThat(forwarded).isNotNull();
    }

    // ==================== ⑨ 一键回退 / 凭据未配置 ====================

    @Test
    @DisplayName("[网关] admin-auth.enabled=false 一键回退：只剥离伪造头，不验签不注入（管理端回到单体拦截器）")
    void disabledModeStripsOnly() {
        AdminIdentityFilter off = new AdminIdentityFilter(jwtUtil, redis(Map.of()).template(), GATEWAY_TOKEN, false);

        // 连"会员令牌打后台"也不在这里拒绝：回退态下由单体 AdminAuthInterceptor 产出文案（C1 安全方向）
        ServerWebExchange forwarded = forward(off, MockServerHttpRequest.get("/api/admin/product/page")
                .header("Authorization", "Bearer " + memberToken())
                .header("X-Admin-Id", "1")
                .build());

        assertThat(forwarded).as("回退态下必须原样放行（由单体判断）").isNotNull();
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Id")).as("伪造头仍要剥离").isNull();
        assertThat(forwarded.getRequest().getHeaders().getFirst("Authorization")).isNotNull();
    }

    @Test
    @DisplayName("[网关] 共享凭据未配置 → 只剥离、不注入、不拒绝（不给下游无法验证的身份）")
    void blankGatewayTokenDoesNotInjectNorReject() {
        AdminIdentityFilter noToken = new AdminIdentityFilter(jwtUtil, redis(Map.of()).template(), "", true);

        ServerWebExchange forwarded = forward(noToken,
                MockServerHttpRequest.get("/api/admin/product/page").header("Authorization", "Bearer " + adminToken(0L)).build());

        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Gateway-Auth")).isNull();
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Admin-Id")).isNull();
    }
}
