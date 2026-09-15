package com.mall.gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 会员身份过滤器：**网关成为登录态的唯一验证方**（方案 §4.4 ①）。
 *
 * <h2>改造前后</h2>
 * 改造前：每个带 {@code @MemberId} 的请求都由各服务自己 {@code 验签 → 查 ums_member → 读 Redis 令牌版本}
 * （单体 {@code MemberSession}），20+ 个端点、6 个域，且用户中心一挂全站 401。
 * 改造后：网关验一次，把身份**注入请求头**往下传：
 * <pre>
 *   X-Gateway-Auth: &lt;共享密钥&gt;      ← 下游据此判断"这个身份是网关给的，不是客户端伪造的"
 *   X-Member-Id:    &lt;memberId&gt;
 *   X-Member-Ver:   &lt;tokenVersion&gt;
 * </pre>
 *
 * <h2>三条必须遵守的规矩</h2>
 * <ol>
 *   <li><b>先剥离、再注入</b>：客户端自己带的 {@code X-Gateway-Auth}/{@code X-Member-Id}/{@code X-Member-Ver}
 *       一律先删掉。不删的话，任何人只要手写一个 {@code X-Member-Id: 1} 就能变成别人——
 *       这是本过滤器最容易写错、后果最严重的一行。</li>
 *   <li><b>令牌版本号必须在网关校验</b>：{@code mall:token:ver:user:{id}} 与 token 里的 {@code ver} 不一致
 *       → 401。这是"登出/改密/被禁用后旧 token 立即失效"的**唯一机制**；
 *       版本键读不到（Redis 故障）时 fail-open 放行——与单体的口径一致，
 *       不因缓存故障把全体用户踢下线。</li>
 *   <li><b>只处理会员令牌，不碰管理端令牌</b>：{@code typ=admin} 的请求**原样透传**。
 *       管理端登录态由本包内的 {@link AdminIdentityFilter}（{@code HIGHEST_PRECEDENCE + 6}，
 *       即排在本过滤器之后）负责——P7 起网关已是管理端登录态的唯一验证方，不再是"单体拦截器"。
 *       这里若"看到非 user 就拒绝"，管理端会当场全挂。</li>
 * </ol>
 *
 * <h2>匿名与失败</h2>
 * 没有 {@code Authorization} 头 → 直接放行（公开接口本来就允许游客），由下游决定是否需要登录；
 * 令牌非法/过期 → <b>HTTP 200 + code=401</b>，文案与单体逐字一致（C1：HTTP 恒 200）。
 *
 * <p>一键回退：{@code mall.gateway.auth.enabled=false} 时只做"剥离伪造头"，不做验证也不注入身份，
 * 登录态立刻回到"各服务自己验"的老路（下游的回退路径还在，见单体 {@code MemberSession}）。
 */
@Component
@
public class MemberIdentityFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(MemberIdentityFilter.class);

    /** 身份注入头：三个都由网关写入，客户端伪造的同名头会被剥离 */
    public static final String HEADER_GATEWAY_AUTH = "X-Gateway-Auth";
    public static final String HEADER_MEMBER_ID = "X-Member-Id";
    public static final String HEADER_MEMBER_VER = "X-Member-Ver";

    /** 与单体 {@code MemberSession} 逐字一致的文案（C1） */
    private static final String MSG_INVALID = "登录已失效，请重新登录";

    private final GatewayJwtUtil jwtUtil;
    private final ReactiveStringRedisTemplate redis;
    private final String gatewayAuthToken;
    private final boolean enabled;

    public MemberIdentityFilter(GatewayJwtUtil jwtUtil,
                               ReactiveStringRedisTemplate redis,
                               @Value("${mall.gateway.auth-token:}") String gatewayAuthToken,
                               @Value("${mall.gateway.auth.enabled:true}") boolean enabled) {
        this.jwtUtil = jwtUtil;
        this.redis = redis;
        this.gatewayAuthToken = gatewayAuthToken == null ? "" : gatewayAuthToken;
        this.enabled = enabled;
        if (enabled && this.gatewayAuthToken.isBlank()) {
            // 不阻止启动：此时不注入身份，下游会自动回落到"自己验签"的老路（安全但慢）。
            // 与"忘了配置就完全开放"相反——本过滤器宁可少给身份，也不给一个下游无法验证的身份。
            log.warn("mall.gateway.auth-token 未配置：网关不会注入会员身份（下游将回落到自行验签）");
        }
        log.info("会员身份过滤器: enabled={}, 身份注入={}", enabled,
                enabled && !this.gatewayAuthToken.isBlank() ? "开启" : "关闭");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // ① 剥离客户端伪造的身份头（无论鉴权开关如何，这一步都不能省）
        ServerHttpRequest stripped = request.mutate()
                .headers(h -> {
                    h.remove(HEADER_GATEWAY_AUTH);
                    h.remove(HEADER_MEMBER_ID);
                    h.remove(HEADER_MEMBER_VER);
                })
                .build();

        if (!enabled || gatewayAuthToken.isBlank()) {
            return chain.filter(exchange.mutate().request(stripped).build());
        }

        String token = bearerToken(stripped);
        if (token == null) {
            // 匿名请求：公开接口要放行，是否要求登录由下游按方法签名决定
            return chain.filter(exchange.mutate().request(stripped).build());
        }

        GatewayJwtUtil.Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (InvalidTokenException e) {
            return reject(exchange, MSG_INVALID);
        }

        // ② 管理端令牌不归本过滤器管（P7 之前仍由单体校验）
        if (!GatewayJwtUtil.TYPE_USER.equals(claims.type())) {
            return chain.filter(exchange.mutate().request(stripped).build());
        }

        // ③ 令牌版本号：登出/改密/禁用后旧 token 立即失效的唯一机制
        return versionMatches(claims)
                .flatMap(ok -> {
                    if (!ok) {
                        log.info("网关拦截失效令牌: memberId={} ver={}", claims.userId(), claims.ver());
                        return reject(exchange, MSG_INVALID);
                    }
                    ServerHttpRequest withIdentity = stripped.mutate()
                            .header(HEADER_GATEWAY_AUTH, gatewayAuthToken)
                            .header(HEADER_MEMBER_ID, String.valueOf(claims.userId()))
                            .header(HEADER_MEMBER_VER, String.valueOf(claims.ver()))
                            .build();
                    return chain.filter(exchange.mutate().request(withIdentity).build());
                });
    }

    /**
     * 令牌版本比对。Redis 不可用（读失败）→ {@code true}（fail-open）：
     * 与单体 {@code TokenVersionService.matches} 的口径一致——缓存故障不该让全站用户被踢下线。
     */
    private Mono<Boolean> versionMatches(GatewayJwtUtil.Claims claims) {
        String key = "mall:token:ver:user:" + claims.userId();
        return redis.opsForValue().get(key)
                .map(v -> {
                    if (v == null || v.isBlank()) {
                        return claims.ver() == 0L;   // 键不存在 = 版本 0（与单体 current() 的语义一致）
                    }
                    try {
                        return Long.parseLong(v.trim()) == claims.ver();
                    } catch (NumberFormatException e) {
                        return true;             // 值不是数字：按"不可判定"处理，不误伤
                    }
                })
                .defaultIfEmpty(claims.ver() == 0L)
                .onErrorResume(e -> {
                    log.warn("网关读取令牌版本失败，本次放行(fail-open): {}", e.getMessage());
                    return Mono.just(true);
                });
    }

    private static String bearerToken(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** 失败响应：HTTP 200 + 统一响应体（业务码 401，文案与单体逐字一致） */
    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 排在 InternalPathBlockFilter(HIGHEST_PRECEDENCE) 之后、限流(+10) 之前：
        // 被挡掉的 /internal/** 不做身份校验；限流则可以在身份确定后再计数
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}
