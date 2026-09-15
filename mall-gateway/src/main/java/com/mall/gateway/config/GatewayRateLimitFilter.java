package com.mall.gateway.config;

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
import java.time.Duration;
import java.util.Optional;

/**
 * 网关全局限流：把"粗粒度、按调用方"的限流上移到入口（方案 §4.7）。
 *
 * <h2>与单体限流的分工</h2>
 * 单体里已有 {@code RateLimitAspect}（按接口维度：上传 20/分、注册 10/分…）。
 * 本过滤器是<b>另一层</b>：按调用方整体的粗粒度总量控制，挡在入口上——
 * 两级阈值不同、用途不同，都保留（方案 §4.7 的"主限流上移、关键接口保留兜底"）。
 *
 * <h2>刻意对齐既有实现的四个细节</h2>
 * <ol>
 *   <li><b>Key 规范</b>：{@code mall:rl:gw:{identity}}——与单体的 {@code mall:rl:{scope}:{identity}} 同前缀、
 *       不同 scope，两套计数互不干扰，也能用同一套运维视角看 Redis。</li>
 *   <li><b>超限仍返回 HTTP 200 + 业务码 429</b>：对外契约是"HTTP 恒 200、结果看 code"
 *       （《接口文档.md》§1.3）。若这里返回 HTTP 429，前端的 axios 会把它当成网络错误，
 *       拿不到"操作过于频繁"的提示——所以<b>不能</b>直接用 Spring Cloud Gateway 自带的
 *       {@code RequestRateLimiter}（它固定回 HTTP 429）。</li>
 *   <li><b>文案与单体逐字一致</b>：{@code 操作过于频繁，请稍后再试}（同一份用户体验）。</li>
 *   <li><b>Redis 不可用则 fail-open</b>：限流是保护措施，不是业务规则；
 *       Redis 抖动时宁可放行也不要让全站不可用（与单体的 fail-open 口径一致）。
 *       <b>fail-open 的作用范围只有 Redis 这一步</b>——下游服务的故障必须原样抛给客户端，
 *       不能被这里吞掉（见 {@link #increment(String)} 的缺陷说明）。</li>
 * </ol>
 *
 * <h2>身份维度</h2>
 * 默认按<b>传输层来源地址</b>计数，<b>不信任</b> {@code X-Forwarded-For}/{@code X-Real-IP}——
 * 这两个头客户端可以随便伪造，直接采信等于把限流开关交给攻击者（换个假 IP 就是全新计数桶）。
 * 部署在可信代理之后才打开 {@code mall.gateway.rate-limit.trust-forwarded-for}，并要求前置代理
 * <b>覆盖</b>（而非追加）转发头。这与单体 {@code RateLimitAspect} 的安全默认完全一致。
 *
 * <p>后续（§4.4）网关开始做 JWT 预校验后，本过滤器的身份维度应优先取解析出的会员 id
 * （像单体的 {@code u{memberId}} 那样），IP 仅作未登录时的回落。
 */
@Component
public class GatewayRateLimitFilter implements GlobalFilter, Ordered {

    /**
     * 用原生 SLF4J 而不是 Lombok 的 {@code @Slf4j}：网关没有（也不需要）Lombok 依赖，
     * 而 JDK 21 下引入 Lombok 还必须显式配注解处理器路径（单体 pom 里记过这个坑）。
     */
    private static final Logger log = LoggerFactory.getLogger(GatewayRateLimitFilter.class);

    /** 与单体同一套 key 前缀，scope 用 gw 区分 */
    private static final String KEY_PREFIX = "mall:rl:gw:";

    private final ReactiveStringRedisTemplate redis;

    @Value("${mall.gateway.rate-limit.enabled:true}")
    private boolean enabled;

    /** 固定窗口内的请求上限 */
    @Value("${mall.gateway.rate-limit.limit:300}")
    private int limit;

    @Value("${mall.gateway.rate-limit.window-seconds:60}")
    private int windowSeconds;

    @Value("${mall.gateway.rate-limit.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    public GatewayRateLimitFilter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        String key = KEY_PREFIX + identity(exchange.getRequest());

        return increment(key)
                .flatMap(count -> count.isEmpty()
                        // 计数不可用（Redis 抖动）⇒ fail-open 放行；chain 只调用一次、也不进限流判定。
                        ? chain.filter(exchange)
                        : ensureWindowTtl(key, count.get()).then(Mono.defer(() -> {
                            if (count.get() > limit) {
                                log.warn("网关限流: key={} count={}/{} window={}s", key, count.get(), limit, windowSeconds);
                                return reject(exchange);
                            }
                            return chain.filter(exchange);
                        })));
    }

    /**
     * 计数（固定窗口）的唯一一次 Redis 调用。<b>fail-open 的作用范围严格止步于这里</b>：
     * Redis 不可用 ⇒ {@code Optional.empty()}（放行），其余异常一律向上抛。
     *
     * <p>⚠️ 这里曾经是本类的一个真实缺陷（P7 收口演练发现）：早先的写法是
     * {@code increment(...).flatMap(放行/限流…).onErrorResume(… chain.filter(exchange))}，
     * 那个 {@code onErrorResume} 挂在<b>整条链</b>上 —— 于是 {@code chain.filter(exchange)} 里
     * 任何下游故障（例如 {@code lb://mall-admin} 的实例不可达）都会被它当成"限流计数失败"吞掉，
     * 然后<b>第二次</b>调用 {@code chain.filter(exchange)}。现象是：下游服务已停，
     * 客户端却拿到 <b>HTTP 200 + 空 body</b>（既不是契约体的三字段，也不是 503），
     * 同时日志里出现一条误导性的"限流计数失败"。这条回归由
     * {@code GatewayRateLimitFilterTest#downstreamFailureMustNotBeSwallowed} 钉住。
     */
    private Mono<Optional<Long>> increment(String key) {
        return redis.opsForValue().increment(key)
                .map(Optional::ofNullable)
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(e -> {
                    // fail-open：Redis 不可用时放行（限流是保护措施，不该成为全站单点）
                    log.warn("网关限流计数失败，本次放行(fail-open): {}", e.getMessage());
                    return Mono.just(Optional.empty());
                });
    }

    /**
     * 首次计数时设置窗口过期（固定窗口：到期后计数归零）。
     * 仅 {@code count == 1} 时才写 TTL；写失败同样是 fail-open（TTL 没设上顶多让计数多留一会儿，
     * 不该因此让请求失败），但与 {@link #increment} 一样<b>只吞 Redis 自己的错误</b>。
     */
    private Mono<Boolean> ensureWindowTtl(String key, long count) {
        if (count != 1L) {
            return Mono.just(Boolean.TRUE);
        }
        return redis.expire(key, Duration.ofSeconds(windowSeconds))
                .defaultIfEmpty(Boolean.TRUE)
                .onErrorResume(e -> {
                    log.warn("网关设置限流窗口失败，本次放行(fail-open): {}", e.getMessage());
                    return Mono.just(Boolean.TRUE);
                });
    }

    /** 超限响应：HTTP 200 + 统一响应体（业务码 429，文案与单体一致） */
    private Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"code\":429,\"message\":\"操作过于频繁，请稍后再试\",\"data\":null}"
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private String identity(ServerHttpRequest request) {
        if (trustForwardedFor) {
            String xff = request.getHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int idx = xff.indexOf(',');
                return idx > 0 ? xff.substring(0, idx).trim() : xff.trim();
            }
            String real = request.getHeaders().getFirst("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return real;
            }
        }
        var remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? "unknown"
                : remote.getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        // 排在 InternalPathBlockFilter(HIGHEST_PRECEDENCE) 之后：
        // 被网关挡掉的 /internal/** 不该计入限流桶
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
