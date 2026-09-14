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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理端身份过滤器（P7 §2/§2.5）：**网关成为管理端登录态的唯一验证方**，与会员侧
 * {@link MemberIdentityFilter} 同构（同一种形状、同一种文案、同一种 fail-open 口径）。
 *
 * <h2>改造前后</h2>
 * 改造前：{@code /api/admin/**} 的鉴权在**单体** {@code AdminAuthInterceptor} →
 * {@code AdminSession}（HS256 验签 → {@code typ=admin} → {@code sys_user} 存在 → {@code status=1} → 令牌版本），
 * 每服务各自验签（product 的 P6-1b 拦截器只验签 + typ，是过渡措施）。
 * 改造后：网关验一次，把身份**注入请求头**往下传：
 * <pre>
 *   X-Gateway-Auth: &lt;共享凭据，与会员侧同一把&gt;   ← 下游据此判断"这个身份是网关给的，不是客户端伪造的"
 *   X-Admin-Id:     &lt;管理员 id（= JWT 的 sub）&gt;
 *   X-Admin-Ver:    &lt;令牌版本号（= JWT 的 ver）&gt;
 * </pre>
 *
 * <h2>三条文案由**本过滤器**产出（逐字照抄单体，C1 不许漂移）</h2>
 * <ol>
 *   <li>头缺失/不是 {@code Bearer xxx} → {@code 401 未登录}（单体 {@code AuthHeader.bearer}）；</li>
 *   <li>验签失败 / 过期 / 令牌版本不符 → {@code 401 登录已失效，请重新登录}（单体 {@code JwtUtil.parse}
 *       与 {@code AdminSession} 的版本分支）；</li>
 *   <li>{@code typ != admin} → {@code 401 登录已失效，请使用管理员账号登录}
 *       —— 这条**只有在服务端能看到 token 类型时**才可能出现，网关统一后归网关（P7 §2 明确要求照抄）；</li>
 *   <li>状态缓存说"已禁用" → {@code 403 账号已被禁用}（单体 {@code AdminSession} 的 {@code status != 1} 分支，
 *       见下面 §状态缓存）。</li>
 * </ol>
 * 全部是 <b>HTTP 200 + 业务码</b>（本项目对外契约"HTTP 恒 200"，见《接口文档.md》§1.3）。
 *
 * <h2>§状态缓存：{@code 403 账号已被禁用} 由谁产出（P7 §2.5 定案）</h2>
 * {@code sys_user} 搬进 {@code mall-admin} 之后，<b>网关手里没有这个状态</b>，而"禁用管理员"若不 bump 令牌版本，
 * 版本校验也拦不住他。定案（与 P3 会员侧同构）：<b>网关读状态缓存，不查库</b>（网关没有、也不该有 datasource）。
 * <pre>
 *   key  : mall:cache:admin:status:{adminId}
 *   值    : ① {"adminId":9,"username":"admin","status":0}   ← 与会员侧 MemberStatusVO 同构（推荐）
 *           ② 裸数字 "0" / "1"                              ← 只想表达状态的简化写法
 *   判据  : 值可解析且 != 1（见单体 EnableStatus.ENABLED）⇒ 禁用 ⇒ 403
 *   缺失 / 值不可解析 / Redis 不可用 ⇒ <b>fail-open 放行</b>（交给下游与令牌版本兜底）
 * </pre>
 * ⚠️ <b>今天这个键还没有写入方</b>（写入方随 {@code mall-admin} 服务落地：改状态时写/删本键，
 * <b>并且</b> bump {@code mall:token:ver:admin:{id}} 做双保险）⇒ 现在这条检查**必须是不可见的**：
 * 键不存在 → fail-open → 请求照旧到下游/单体。这也是"本过滤器必须纯增量"的一部分。
 * <p>⚠️ 判定顺序刻意与单体一致：<b>先状态、后版本</b>（单体 {@code AdminSession.resolve} 也是先 {@code status}
 * 再 {@code tokenVersionService.matches}）——否则"已禁用 + 版本已 bump"的管理员会拿到 401 而不是 403（文案漂移）。
 *
 * <h2>§顺序：本过滤器必须排在 {@link MemberIdentityFilter}（{@code HIGHEST_PRECEDENCE + 5}）**之后**</h2>
 * 两个原因，缺一不可：
 * <ol>
 *   <li>{@code MemberIdentityFilter} 会**无条件剥离** {@code X-Gateway-Auth}（它不区分会员/管理端路径）。
 *       若本过滤器排在它**前面**并注入了 {@code X-Gateway-Auth}，随后就会被它删掉 ⇒ 下游拿到一个
 *       "没有凭据的 {@code X-Admin-Id}" ⇒ 管理端全线"未登录"。所以注入必须发生在**剥离之后**。</li>
 *   <li>反过来，本过滤器**只在 {@code /api/admin/**} 范围内剥离 {@code X-Gateway-Auth}**：
 *       若在会员路径上也剥，就会把会员过滤器刚注入的会员身份删掉（P3 行为必须逐字不变）。</li>
 * </ol>
 * 落在 {@code +6}：仍在限流（{@code +10}）之前——限流应当在身份确定之后计数（与会员侧同一考虑）。
 *
 * <h2>§可回退（P7 §7：管理端是"人的入口"，回滚优先级最高）</h2>
 * {@code mall.gateway.admin-auth.enabled=false} ⇒ 不验签、不读 Redis、不注入身份，**只剥离伪造头**，
 * 管理端登录态立刻回到"单体 {@code AdminAuthInterceptor} 负责"的老路（下游回退路径还在）。
 * ⚠️ 本开关**独立于** {@code mall.gateway.auth.enabled}：会员侧一键回退只停会员侧，
 * 不会**静默**把管理端验签一起关掉（管理端的回退必须是显式的）。
 * <p>{@code mall.gateway.auth-token} 未配置时同样只剥离、不注入（与会员侧同一条规矩）：
 * 宁可少给身份，也不给一个下游无法验证的身份。
 */
@Component
public class AdminIdentityFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AdminIdentityFilter.class);

    // ==================== 对外契约①：注入头名（下游 mall-admin / product 照此读取） ====================

    /** 与会员侧**同一把**共享凭据（{@code mall.gateway.auth-token}）：下游据此判断身份来自网关 */
    public static final String HEADER_GATEWAY_AUTH = "X-Gateway-Auth";
    public static final String HEADER_ADMIN_ID = "X-Admin-Id";
    public static final String HEADER_ADMIN_VER = "X-Admin-Ver";

    /** 客户端伪造的管理端身份头（任何路径都不许活下来）：{@code X-Admin-*} */
    private static final String ADMIN_HEADER_PREFIX = "x-admin-";

    // ==================== 对外契约②：Redis key 名（写入方 mall-admin 必须逐字相同） ====================

    /** 管理端令牌版本号：{@code mall:token:ver:admin:{id}}（与单体 {@code CacheKeys.tokenVersion("admin", id)} 同构） */
    static final String KEY_TOKEN_VERSION = "mall:token:ver:admin:";

    /** 管理员状态缓存（P7 §2.5）：{@code mall:cache:admin:status:{id}} */
    static final String KEY_ADMIN_STATUS = "mall:cache:admin:status:";

    // ==================== 对外契约③：文案（逐字照抄单体，C1 判据之一） ====================

    static final String MSG_NOT_LOGIN = "未登录";
    static final String MSG_INVALID = "登录已失效，请重新登录";
    static final String MSG_NOT_ADMIN = "登录已失效，请使用管理员账号登录";
    static final String MSG_DISABLED = "账号已被禁用";

    /** 单体 {@code EnableStatus.ENABLED}：{@code sys_user.status} 0 禁用 / 1 正常 */
    private static final long STATUS_ENABLED = 1L;

    /** 拦截范围：与单体 {@code WebMvcConfig} 的 {@code addPathPatterns("/api/admin/**")} 对应 */
    private static final String PATH_ADMIN = "/api/admin";

    /** 唯一放行路径：与单体 {@code excludePathPatterns("/api/admin/auth/login")} 逐字对应 */
    private static final String PATH_LOGIN = "/api/admin/auth/login";

    /** 状态缓存里的 {@code "status"} 字段（含字符串写法 {@code "status":"0"}） */
    private static final Pattern STATUS_FIELD = Pattern.compile("\"status\"\\s*:\\s*\"?(\\d+)\"?");

    /** 裸数字写法（整串就是一个数字） */
    private static final Pattern BARE_NUMBER = Pattern.compile("-?\\d+");

    private final GatewayJwtUtil jwtUtil;
    private final ReactiveStringRedisTemplate redis;
    private final String gatewayAuthToken;
    private final boolean enabled;

    public AdminIdentityFilter(GatewayJwtUtil jwtUtil,
                               ReactiveStringRedisTemplate redis,
                               @Value("${mall.gateway.auth-token:}") String gatewayAuthToken,
                               @Value("${mall.gateway.admin-auth.enabled:true}") boolean enabled) {
        this.jwtUtil = jwtUtil;
        this.redis = redis;
        this.gatewayAuthToken = gatewayAuthToken == null ? "" : gatewayAuthToken;
        this.enabled = enabled;
        if (enabled && this.gatewayAuthToken.isBlank()) {
            // 不阻止启动：此时不注入身份，管理端登录态仍由单体负责（安全但慢），而不是"忘了配置就完全开放"。
            log.warn("mall.gateway.auth-token 未配置：网关不会注入管理端身份（下游将回落到自行验签/单体拦截器）");
        }
        log.info("管理端身份过滤器: enabled={}, 身份注入={}", enabled,
                enabled && !this.gatewayAuthToken.isBlank() ? "开启" : "关闭");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        boolean adminScoped = isAdminScoped(path);

        // ① 剥离客户端伪造的身份头（无论鉴权开关如何，这一步都不能省）
        //    · X-Admin-*：任何路径都剥——除了本过滤器，没有任何合法来源会写这些头；
        //    · X-Gateway-Auth：**只在管理端路径**剥（会员路径上的那个头是会员过滤器刚注入的，剥掉就是 P3 回归）。
        ServerHttpRequest stripped = stripForgeable(request, adminScoped);

        // ② 只管 /api/admin/**，且放行登录路径（与单体 excludePathPatterns 一致）。
        //    其它路径**完全不介入**：会员侧行为逐字不变。
        if (!adminScoped || PATH_LOGIN.equals(path)) {
            return chain.filter(withRequest(exchange, stripped));
        }

        if (!enabled || gatewayAuthToken.isBlank()) {
            // 一键回退态：只剥离、不验签、不注入 —— 单体 AdminAuthInterceptor 仍是执行者
            return chain.filter(withRequest(exchange, stripped));
        }

        String token = bearerToken(stripped);
        if (token == null) {
            // 与单体 AuthHeader.bearer 逐字一致：缺失/格式错 ⇒ 401「未登录」
            return reject(exchange, 401, MSG_NOT_LOGIN);
        }

        GatewayJwtUtil.Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (InvalidTokenException e) {
            // 验签失败 / 过期 / 结构非法 ⇒ 401「登录已失效，请重新登录」
            return reject(exchange, 401, MSG_INVALID);
        }

        // ③ 双体系隔离：会员令牌不得用于后台接口（即使 sub 恰好与某个管理员 id 相同）
        if (!GatewayJwtUtil.TYPE_ADMIN.equals(claims.type())) {
            log.info("网关拦截非管理员令牌访问管理端: path={} typ={} sub={}", path, claims.type(), claims.userId());
            return reject(exchange, 401, MSG_NOT_ADMIN);
        }

        // ④ 先状态（403）后版本（401）——判定顺序与单体 AdminSession.resolve 一致
        return statusDisabled(claims).flatMap(disabled -> {
            if (disabled) {
                log.info("网关拦截已禁用管理员: adminId={} path={}", claims.userId(), path);
                return reject(exchange, 403, MSG_DISABLED);
            }
            return versionMatches(claims).flatMap(ok -> {
                if (!ok) {
                    log.info("网关拦截失效管理端令牌: adminId={} ver={}", claims.userId(), claims.ver());
                    return reject(exchange, 401, MSG_INVALID);
                }
                ServerHttpRequest withIdentity = stripped.mutate()
                        .header(HEADER_GATEWAY_AUTH, gatewayAuthToken)
                        .header(HEADER_ADMIN_ID, String.valueOf(claims.userId()))
                        .header(HEADER_ADMIN_VER, String.valueOf(claims.ver()))
                        .build();
                return chain.filter(withRequest(exchange, withIdentity));
            });
        });
    }

    /**
     * 状态缓存判定（P7 §2.5）：缓存里写着"禁用" ⇒ {@code true}（调用方回 403）。
     *
     * <p><b>fail-open</b>：键不存在（今天就是这种状态——还没有写入方）、值不可解析、Redis 读失败
     * 一律返回 {@code false}（放行）。与会员侧同一条理由：缓存是加速层，不是唯一判据；
     * 真正的"立即失效"由令牌版本号保证。而"宁可放行"还多一条理由：本过滤器必须**纯增量**，
     * 不能因为一个还没人写的键把管理端挡在门外。
     *
     * <p>⚠️ <b>不查库</b>：网关没有、也不该有 datasource（P7 §2 "网关不查库"）。
     */
    private Mono<Boolean> statusDisabled(GatewayJwtUtil.Claims claims) {
        String key = KEY_ADMIN_STATUS + claims.userId();
        return redis.opsForValue().get(key)
                .map(raw -> saysDisabled(key, raw))
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    log.warn("网关读取管理员状态缓存失败，本次放行(fail-open): key={} err={}", key, e.getMessage());
                    return Mono.just(false);
                });
    }

    /** 缓存值是否表示"已禁用"；不可解析 ⇒ false（fail-open，但留 warn 便于发现写入方写错了格式） */
    private static boolean saysDisabled(String key, String raw) {
        Long status = extractStatus(raw);
        if (status == null) {
            log.warn("网关无法解析管理员状态缓存值，按 fail-open 放行: key={} value=[{}]", key, raw);
            return false;
        }
        return status != STATUS_ENABLED;
    }

    /**
     * 从缓存值里取出 {@code status}：支持 JSON（{@code {"adminId":9,...,"status":0}}，与会员侧同构）
     * 与裸数字（{@code "0"}）两种写法。取不到 ⇒ {@code null}（"不可判定"）。
     */
    private static Long extractStatus(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        Matcher field = STATUS_FIELD.matcher(value);
        if (field.find()) {
            return parseLongOrNull(field.group(1));
        }
        if (BARE_NUMBER.matcher(value).matches()) {
            return parseLongOrNull(value);
        }
        return null;
    }

    private static Long parseLongOrNull(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 令牌版本比对（{@code mall:token:ver:admin:{id}}）。Redis 不可用（读失败）→ {@code true}（fail-open）：
     * 与单体 {@code TokenVersionService.matches} 的口径一致——缓存故障不该把全体管理员踢下线。
     */
    private Mono<Boolean> versionMatches(GatewayJwtUtil.Claims claims) {
        String key = KEY_TOKEN_VERSION + claims.userId();
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
                    log.warn("网关读取管理端令牌版本失败，本次放行(fail-open): key={} err={}", key, e.getMessage());
                    return Mono.just(true);
                });
    }

    /**
     * 拦截范围：与单体 {@code addPathPatterns("/api/admin/**")} 同义 ——
     * {@code /api/admin}、{@code /api/admin/**} 在内，{@code /api/administrator/**}（不是同一段）不在内。
     */
    static boolean isAdminScoped(String path) {
        if (!path.startsWith(PATH_ADMIN)) {
            return false;
        }
        return path.length() == PATH_ADMIN.length() || path.charAt(PATH_ADMIN.length()) == '/';
    }

    /** 先剥离、再注入：伪造的 {@code X-Gateway-Auth}/{@code X-Admin-*} 不许活到下游 */
    private static ServerHttpRequest stripForgeable(ServerHttpRequest request, boolean stripGatewayAuth) {
        List<String> forgeable = new ArrayList<>();
        // ⚠️ Spring Framework 7 的 HttpHeaders 不再是 Map：取头名用 headerNames()（不是 keySet()）
        for (String name : request.getHeaders().headerNames()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(ADMIN_HEADER_PREFIX) || (stripGatewayAuth && HEADER_GATEWAY_AUTH.equalsIgnoreCase(name))) {
                forgeable.add(name);
            }
        }
        if (forgeable.isEmpty()) {
            return request;
        }
        return request.mutate().headers(h -> forgeable.forEach(h::remove)).build();
    }

    private static ServerWebExchange withRequest(ServerWebExchange exchange, ServerHttpRequest request) {
        return request == exchange.getRequest() ? exchange : exchange.mutate().request(request).build();
    }

    /** 与会员侧 {@code MemberIdentityFilter.bearerToken} 逐字同形（同一套头解析口径） */
    private static String bearerToken(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** 失败响应：HTTP 200 + 统一响应体（业务码 401/403，文案与单体逐字一致） */
    private Mono<Void> reject(ServerWebExchange exchange, int code, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // +6：必须晚于 MemberIdentityFilter(+5) 的"剥离 X-Gateway-Auth"（否则注入的凭据会被它删掉），
        //     早于限流(+10)（身份确定后再计数）。理由见类注释「§顺序」。
        return Ordered.HIGHEST_PRECEDENCE + 6;
    }
}
