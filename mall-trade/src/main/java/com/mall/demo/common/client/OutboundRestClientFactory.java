package com.mall.demo.common.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 出站 {@link RestClient} 的**统一装配**（与 {@code mall-admin} 的
 * {@code com.mall.admin.config.OutboundRestClientFactory} 同构）。
 *
 * <h2>为什么要它</h2>
 * {@code common/client} 下 5 个出站客户端原先各写一遍这段装配（{@code lb://} 分支 / 直连分支 /
 * 连接超时 / 读超时 / 内部令牌头），5 份几乎相同的代码，且**直连分支容易漏东西**：
 * {@link OutboundHeadersInterceptor} 只挂在 {@code @LoadBalanced RestClient.Builder} 上
 * （见 {@link OutboundClientConfig}），而 {@code http://} 直连分支用的是普通
 * {@code RestClient.builder()} ⇒ 走直连时**不会带内部令牌头**，下游校验会 401
 * （排障时最容易被指向错误方向的一类问题）。
 *
 * <h2>两条分支的头处理（刻意不同，避免重复拦截）</h2>
 * <ul>
 *   <li>{@code lb://} ⇒ 用传入的 {@code @LoadBalanced} builder；它上面**已经**挂了
 *       {@link OutboundHeadersInterceptor}，这里不再加第二个；</li>
 *   <li>{@code http://} ⇒ 普通 builder + **显式加同一个拦截器**，于是"直连排障模式"与"生产模式"
 *       发出的头完全一致（{@code X-Internal-Token} / {@code X-Trace-Id}）。</li>
 * </ul>
 *
 * <p>本类与拦截器同包（{@code com.mall.demo.common.client}），故 {@link OutboundHeadersInterceptor} 无需 import。
 */
@Component
public class OutboundRestClientFactory {

    private final String internalToken;

    public OutboundRestClientFactory(@Value("${mall.internal.token:}") String internalToken) {
        this.internalToken = internalToken;
    }

    /**
     * 装配一个出站 RestClient。
     *
     * @param loadBalancedBuilder 注入的 {@code @LoadBalanced RestClient.Builder}（{@code lb://} 时使用）
     * @param baseUrl             {@code lb://服务名} 或 {@code http://host:port}（排障/演练）
     * @param connectTimeoutMs    连接超时（关键路径上的域各有各的值，别统一成一个）
     * @param readTimeoutMs       读超时
     */
    public RestClient build(RestClient.Builder loadBalancedBuilder, String baseUrl,
                            long connectTimeoutMs, long readTimeoutMs) {
        RestClient.Builder builder = baseUrl.startsWith("lb://")
                ? loadBalancedBuilder
                : RestClient.builder().requestInterceptor(new OutboundHeadersInterceptor(internalToken));
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
