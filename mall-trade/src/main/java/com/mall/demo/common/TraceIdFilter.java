package com.mall.demo.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
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
 * <h2>与网关的分工</h2>
 * 网关负责"定 id 并透传"（见 {@code mall-gateway} 的 {@code TraceIdFilter}）；
 * 本服务负责"接住它、写进 MDC、用完清理"。两边各自声明头名常量，值必须逐字一致。
 *
 * <h2>三个刻意之处</h2>
 * <ol>
 *   <li><b>没有头时自己生成一个</b>：服务会被直接调用（本机排障、真库套件、内部探针），
 *       那种情况下也应该有 traceId —— 否则"直连路径"的日志反而没有关联 id。</li>
 *   <li><b>形状闸门与网关一致</b>（{@code [A-Za-z0-9_-]{8,64}}）：直连时客户端可以直接塞头，
 *       不校验就等于允许把换行符写进日志（日志注入）。不合规 ⇒ 丢弃、重新生成。</li>
 *   <li><b>用完必清 MDC</b>（{@code finally}）：Tomcat 线程是**复用**的，不清就会把上一个请求的 id
 *       留给下一个请求 —— 那种"日志里 traceId 张冠李戴"的故障比没有 traceId 更难查。</li>
 * </ol>
 *
 * <p>顺序用 {@link Ordered#HIGHEST_PRECEDENCE}：要在**所有**其它过滤器/拦截器之前把 MDC 放好，
 * 这样连"鉴权失败"那条日志也带得上 id（排查时最需要的往往正是它）。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 与网关逐字一致的头名 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    /** MDC 键名：logback/log4j 的 pattern 里用 %X{traceId} 取它 */
    public static final String MDC_KEY = "traceId";

    /** 与网关同一套形状闸门（防日志注入：换行/空格/超长一律丢弃） */
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
            MDC.remove(MDC_KEY);   // ← 线程复用，必须清
        }
    }
}
