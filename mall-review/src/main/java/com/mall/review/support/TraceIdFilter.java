package com.mall.review.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * traceId 接收端（P8-4 可观测性）：把网关给的 {@code X-Trace-Id} 放进 MDC，让**本服务的每一行日志**都带上它。
 *
 * <p>与 {@code mall-trade} 里那份**逐字同源**（唯一差别是包名）：
 * 一次经网关的调用会穿过网关 + 若干微服务，没有关联 id 时排一个跨服务故障只能靠"时间戳挨着看"；
 * 有了它，在任意服务的日志里搜到 id 就能把这次调用的**全链路日志**串起来。
 *
 * <h2>三个刻意之处</h2>
 * <ol>
 *   <li><b>没有头时自己生成一个</b>：服务会被直接调用（本机排障、真库套件、内部探针），
 *       那种情况下也应该有 traceId，否则"直连路径"的日志反而没有关联 id。</li>
 *   <li><b>形状闸门</b>（{@code [A-Za-z0-9_-]{8,64}}）：直连时客户端可以直接塞头，
 *       不校验就等于允许把换行符写进日志（日志注入）。不合规 ⇒ 丢弃、重新生成。</li>
 *   <li><b>用完必清 MDC</b>（{@code finally}）：Tomcat 线程是**复用**的，不清就会把上一个请求的 id
 *       留给下一个请求 —— 那种"日志里 traceId 张冠李戴"的故障比没有 traceId 更难查。</li>
 * </ol>
 *
 * <p>顺序 {@link Ordered#HIGHEST_PRECEDENCE}：要在所有其它过滤器/拦截器之前把 MDC 放好，
 * 这样连"鉴权失败"那条日志也带得上 id（排查时最需要的往往正是它）。
 *
 * <p>用原生 SLF4J 而不是 Lombok 的 {@code @Slf4j}：少一个依赖假设，七个服务都能原样编译。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    /** 与网关/mall-trade 逐字一致的头名 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    /** MDC 键名：日志 pattern 里用 %X{traceId} 取它 */
    public static final String MDC_KEY = "traceId";

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TRACE_ID_HEADER);
        String traceId = (incoming != null && SAFE.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}