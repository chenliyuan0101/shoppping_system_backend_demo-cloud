package com.mall.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 网关全局限流过滤器的单元测试（不启动整个网关，不需要 Nacos/下游实例）。
 *
 * <p>守四件事：
 * <ol>
 *   <li><b>放行路径</b>：计数正常且未超限 ⇒ 透传下游，且 {@code chain} 只被调用<b>一次</b>；</li>
 *   <li><b>Redis 故障 fail-open</b>：计数抛错 ⇒ 放行，{@code chain} 仍只被调用一次；</li>
 *   <li><b>超限</b>：HTTP 200 + 业务码 429 + 逐字文案，且<b>不</b>触碰下游；</li>
 *   <li><b>回归闸门（本测试存在的首要理由）</b>：<b>下游故障不得被 fail-open 吞掉</b>。
 *       早先的实现把 {@code onErrorResume} 挂在整条链上，导致"下游服务已停"被当成
 *       "限流计数失败"，再第二次调用 {@code chain.filter} ⇒ 客户端拿到 HTTP 200 + 空 body。
 *       现在必须是：错误原样向上抛、{@code chain} 只被调用一次。</li>
 * </ol>
 *
 * <p>刻意<b>不用</b> {@code reactor-test}：网关的测试类路径上没有它（离线仓里也没有），
 * 用 {@code block()} + AssertJ 断言即可，少一个依赖。
 */
@DisplayName("网关全局限流过滤器")
class GatewayRateLimitFilterTest {

    private ReactiveStringRedisTemplate template;
    private ReactiveValueOperations<String, String> ops;
    private GatewayRateLimitFilter filter;
    private final AtomicInteger chainCalls = new AtomicInteger();

    /** 记录 chain 被调用次数，并返回给定结果 */
    private GatewayFilterChain chainReturning(Mono<Void> result) {
        return exchange -> {
            chainCalls.incrementAndGet();
            return result;
        };
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(ReactiveStringRedisTemplate.class);
        ops = mock(ReactiveValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        filter = new GatewayRateLimitFilter(template);
        // @Value 字段在单测里没有 Spring 容器注入，用反射按生产默认值配好
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "limit", 300);
        ReflectionTestUtils.setField(filter, "windowSeconds", 60);
        ReflectionTestUtils.setField(filter, "trustForwardedFor", false);
        chainCalls.set(0);
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/admin/dashboard/summary").build());
    }

    @Test
    @DisplayName("未超限：透传下游，chain 只调用一次，并且写了窗口 TTL")
    void withinLimitPassesThroughOnce() {
        when(ops.increment(anyString())).thenReturn(Mono.just(1L));
        when(template.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(Boolean.TRUE));

        MockServerWebExchange ex = exchange();
        assertThatCode(() -> filter.filter(ex, chainReturning(Mono.empty())).block()).doesNotThrowAnyException();

        assertThat(chainCalls.get()).isEqualTo(1);
        assertThat(ex.getResponse().getStatusCode()).isNull(); // 没有自己写响应
    }

    @Test
    @DisplayName("Redis 计数失败：fail-open 放行，chain 只调用一次")
    void redisFailureFailsOpenOnce() {
        when(ops.increment(anyString()))
                .thenReturn(Mono.error(new RedisConnectionFailureException("Connection refused")));

        assertThatCode(() -> filter.filter(exchange(), chainReturning(Mono.empty())).block())
                .doesNotThrowAnyException();

        assertThat(chainCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("回归闸门：下游故障必须原样抛出，不能被当成限流计数失败吞成空 200")
    void downstreamFailureMustNotBeSwallowed() {
        when(ops.increment(anyString())).thenReturn(Mono.just(7L));

        MockServerWebExchange ex = exchange();
        Mono<Void> downstreamError = Mono.error(
                new ConnectException("Connection refused: getsockopt: /127.0.0.1:8108"));

        assertThatThrownBy(() -> filter.filter(ex, chainReturning(downstreamError)).block())
                .hasRootCauseInstanceOf(ConnectException.class);

        // 关键断言：chain 只能被调用一次（早先的实现会调用两次，第二次产出的就是那个空 200）
        assertThat(chainCalls.get()).isEqualTo(1);
        // 并且本过滤器自己既没写状态码也没写响应体（"空 200" 的两个特征它都不许占）
        assertThat(ex.getResponse().getStatusCode()).isNull();
        assertThat(ex.getResponse().isCommitted()).isFalse();
    }

    @Test
    @DisplayName("超限：HTTP 200 + 业务码 429 + 逐字文案，且不触碰下游")
    void overLimitRejectsWithoutTouchingDownstream() {
        when(ops.increment(anyString())).thenReturn(Mono.just(301L));

        MockServerWebExchange ex = exchange();
        assertThatCode(() -> filter.filter(ex, chainReturning(Mono.empty())).block()).doesNotThrowAnyException();

        assertThat(chainCalls.get()).isZero();
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.getResponse().getBodyAsString().block())
                .isEqualTo("{\"code\":429,\"message\":\"操作过于频繁，请稍后再试\",\"data\":null}");
    }

    @Test
    @DisplayName("开关关闭：直接放行，连 Redis 都不读")
    void disabledSkipsRedisEntirely() {
        ReflectionTestUtils.setField(filter, "enabled", false);

        assertThatCode(() -> filter.filter(exchange(), chainReturning(Mono.empty())).block())
                .doesNotThrowAnyException();

        assertThat(chainCalls.get()).isEqualTo(1);
        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("窗口 TTL 写失败：fail-open 放行（不因为 TTL 写不上而让请求失败）")
    void expireFailureStillPassesThrough() {
        when(ops.increment(anyString())).thenReturn(Mono.just(1L));
        when(template.expire(anyString(), eq(Duration.ofSeconds(60))))
                .thenReturn(Mono.error(new RedisConnectionFailureException("boom")));

        assertThatCode(() -> filter.filter(exchange(), chainReturning(Mono.empty())).block())
                .doesNotThrowAnyException();

        assertThat(chainCalls.get()).isEqualTo(1);
    }
}
