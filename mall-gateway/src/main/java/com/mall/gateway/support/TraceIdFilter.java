package com.mall.gateway.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * traceId 贯穿（P8-4 可观测性）：给每个请求定一个**唯一的关联 id**，写进日志，并透传给下游。
 *
 * <h2>为什么要有它</h2>
 * 一次"经网关"的调用会穿过网关 + 若干微服务，各自的日志是分开的。没有关联 id 时，
 * 排一个跨服务的故障只能靠"时间戳挨着看"——5 个服务、每服务几万行，基本无法定位。
 * 有了 traceId：在任意一个服务的日志里搜到它，就能把这次调用的**全链路日志**串起来。
 *
 * <h2>三条口径（都刻意，写在策略里）</h2>
 * <ol>
 *   <li><b>客户端传来的值只在"形状可信"时才复用</b>：必须匹配 {@code [A-Za-z0-9_-]{8,64}}。
 *       不匹配（空、超长、带空格/换行/控制字符）⇒ **丢弃并重新生成**。
 *       理由与 P7 的身份头不同：traceId 不是权限凭据，复用它对排障有价值（前端/压测工具可以自带 id 串起全链路），
 *       但它会**进日志**，所以必须先过形状闸门，避免日志注入（换行符伪造日志行）。
 *   </li>
 *   <li><b>无论复用还是生成，都**覆盖**请求头再转发</b>：下游只会看到一个网关认可的值，
 *       不会出现"客户端传一个、网关又加一个"的双值歧义。</li>
 *   <li><b>网关自己不打 MDC</b>：Reactor 的线程会在线程池间跳，MDC（ThreadLocal）在网关里不可靠。
 *       网关的做法是**在日志里显式打出这个 id**（见 {@code log.info}），下游（Servlet 栈）才用 MDC。</li>
 * </ol>
 *
 * <p>顺序：{@code HIGHEST_PRECEDENCE + 1} —— 紧跟"内部路径拦截"之后、限流之前。
 * 放在这么前面是为了让**被拦掉的请求也带 traceId**（拦掉的原因同样需要能被串起来）。
 */
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    /** 用原生 SLF4J 而不是 Lombok：网关没有（也不需要）Lombok 依赖（与 GatewayRateLimitFilter 同口径） */
    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    /** 对外契约：请求头名（下游各服务用它取 traceId；两边各自声明常量，值必须逐字一致） */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * 允许复用的形状：8~64 位的字母/数字/下划线/短横线。
     * 关键在于**排除了换行与空格**（日志注入）与超长值（刷日志）。
     */
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String incoming = request.getHeaders().getFirst(TRACE_ID_HEADER);
        String traceId = (incoming != null && SAFE.matcher(incoming).matches()) ? incoming : newTraceId();
        boolean reused = traceId.equals(incoming);

        // ② 覆盖后转发：下游只会看到一个网关认可的值
        ServerHttpRequest mutated = request.mutate()
                .headers(h -> h.set(TRACE_ID_HEADER, traceId))
                .build();

        // ③ 网关侧显式落日志（不用 MDC：Reactor 线程会跳）
        log.info("trace={} 入口 {} {} 来源={}", traceId, request.getMethod(), request.getURI().getRawPath(),
                reused ? "复用客户端" : "网关生成");

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /** 新 id：32 位十六进制里取 16 位（够唯一、又不至于刷屏） */
    static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
