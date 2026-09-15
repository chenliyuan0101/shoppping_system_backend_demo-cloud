package com.mall.demo.common.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 出站请求的统一请求头（P8-4 收尾）。
 *
 * <h2>解决什么</h2>
 * <ol>
 *   <li><b>内部令牌头只写一处</b>：{@code X-Internal-Token} 原先是在每个调用点手工 {@code .header(...)}
 *      （全项目一共 42 处）——漏一处就是一个"本地跑得通、换服务就 401"的坑。现在挂在
 *      {@code @LoadBalanced RestClient.Builder} 上，所有走服务发现的出站调用**自动带上**。</li>
 *   <li><b>traceId 跨服务贯通</b>：本服务作为"调用方"时，把当前请求的 traceId（MDC）透传给下游
 *      ⇒ 一条链路的日志在**每一跳**都能用同一个 id 串起来（原先内部调用到了下游会自己生成新 id，
 *      于是"网关 → mall-admin → mall-trade"三段日志对不上）。</li>
 * </ol>
 *
 * <h2>四个刻意的取舍</h2>
 * <ul>
 *   <li><b>用 {@code set} 而不是 {@code add}</b>：调用点若已手工写了同一个头，这里覆盖成同一个值 ⇒
 *      下游只会看到一个值（不会出现"两个 X-Internal-Token"的歧义）。这也让"逐步去掉手工写法"可以分批进行，
 *      中间态不会出问题。</li>
 *   <li><b>traceId 只在有值时透传</b>：定时任务/MQ 消费者线程没有 MDC ⇒ **不编造** id，
 *      下游会按自己的规则生成（宁可"没有关联"，也不要"关联到一个假 id"）。</li>
 *   <li><b>密钥未配置（空串）时不写这个头</b>：与管理端 fail-closed 的口径一致——空白凭据发出去没有意义，
 *      还会让下游误以为"带了凭据"。</li>
 *   <li><b>不动不透明的东西</b>：不在这里做重试、熔断、日志埋点——那些各自有归属（超时在客户端构造时设、
 *      降级在 service 层判断）。这个拦截器只干"补齐两个头"这一件事。</li>
 * </ul>
 */
public class OutboundHeadersInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OutboundHeadersInterceptor.class);

    /** 与网关/各服务的入站校验共用同一个头名（值必须逐字一致） */
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";
    /** P8-4：与网关 TraceIdFilter、各服务 TraceIdFilter 逐字一致 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    /** MDC 键名（见本服务的 TraceIdFilter） */
    private static final String MDC_TRACE_ID = "traceId";

    private final String internalToken;

    public OutboundHeadersInterceptor(String internalToken) {
        this.internalToken = internalToken;
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("出站内部令牌未配置（mall.internal.token 为空）：出站调用不会带 {}，下游若校验会 401",
                    HEADER_INTERNAL_TOKEN);
        }
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (internalToken != null && !internalToken.isBlank()) {
            request.getHeaders().set(HEADER_INTERNAL_TOKEN, internalToken);
        }
        String traceId = MDC.get(MDC_TRACE_ID);
        if (traceId != null && !traceId.isBlank()) {
            request.getHeaders().set(HEADER_TRACE_ID, traceId);
        }
        return execution.execute(request, body);
    }
}