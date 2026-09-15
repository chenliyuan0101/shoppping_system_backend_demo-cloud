package com.mall.gateway.support;

import com.mall.gateway.config.GatewayRateLimitFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关 traceId 过滤器（P8-4）的单元测试。
 *
 * <p>守三件事：① 合规的客户端值被**复用**；② 不合规/缺失 ⇒ **重新生成**（防日志注入）；
 * ③ 无论哪种情况，**下游只会看到一个**网关认可的值（覆盖而非追加）。
 */
@DisplayName("网关 traceId 过滤器")
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    private ServerHttpRequest runWith(String incoming, AtomicReference<String> seenDownstream) {
        MockServerHttpRequest.BaseBuilder<?> b = MockServerHttpRequest.get("/api/product/1");
        MockServerHttpRequest request = (incoming == null ? b : b.header(TraceIdFilter.TRACE_ID_HEADER, incoming)).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = ex -> {
            List<String> values = ex.getRequest().getHeaders().get(TraceIdFilter.TRACE_ID_HEADER);
            seenDownstream.set(values == null || values.isEmpty() ? null : values.get(0));
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return null;
    }

    private String downstreamSees(String incoming) {
        AtomicReference<String> seen = new AtomicReference<>("UNSET");
        runWith(incoming, seen);
        return seen.get();
    }

    @Test
    @DisplayName("合规的客户端值：原样透传（前端/压测工具可以自带 id 串全链路）")
    void safeIncomingIsReused() {
        assertThat(downstreamSees("trace1234567")).isEqualTo("trace1234567");
    }

    @Test
    @DisplayName("缺失：生成 16 位十六进制")
    void missingIsGenerated() {
        assertThat(downstreamSees(null)).matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("不合规：丢弃并重新生成（换行/空格/超长/过短都算）")
    void unsafeIsReplaced() {
        assertThat(downstreamSees("with space")).matches("[0-9a-f]{16}");
        assertThat(downstreamSees("line\nbreak-inject")).matches("[0-9a-f]{16}");
        assertThat(downstreamSees("short")).matches("[0-9a-f]{16}");
        assertThat(downstreamSees("y".repeat(65))).matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("下游只会看到一个值（覆盖，不是追加）")
    void downstreamSeesExactlyOneValue() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/product/1")
                .header(TraceIdFilter.TRACE_ID_HEADER, "trace1234567")
                .header(TraceIdFilter.TRACE_ID_HEADER, "another12345")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<List<String>> seen = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            seen.set(ex.getRequest().getHeaders().get(TraceIdFilter.TRACE_ID_HEADER));
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertThat(seen.get()).hasSize(1);
        assertThat(seen.get().get(0)).isIn("trace1234567", "another12345");
    }

    @Test
    @DisplayName("顺序排在限流之前（被拦掉的请求也要有 traceId）")
    void orderIsBeforeRateLimit() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(filter.getOrder()).isLessThan(new GatewayRateLimitFilter(null).getOrder());
    }
}
