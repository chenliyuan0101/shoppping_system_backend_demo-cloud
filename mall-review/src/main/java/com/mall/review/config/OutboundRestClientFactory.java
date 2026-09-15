package com.mall.review.config;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 出站 {@link RestClient} 的**统一装配**（P8-7 起，与 mall-admin 的同名类同构）。
 *
 * <h2>为什么要它</h2>
 * 每个出站客户端原先各写一遍这段装配（`lb://` 分支 / 直连分支 / 连接超时 / 读超时 / 内部令牌头），
 * 一共 14 个客户端 ⇒ 14 份几乎相同的代码，且**直连分支容易漏东西**：
 * {@code OutboundHeadersInterceptor} 只挂在 {@code @LoadBalanced RestClient.Builder} 上，
 * 而 {@code http://} 直连分支用的是普通 {@code RestClient.builder()} ⇒ 走直连时**不会带内部令牌头**
 * （本模块的 {@code MemberSnapshotClientTest} 走的就是直连：它要证明的"连不上 ⇒ 空串"这条降级路径
 * 必须在与生产同样的出站装配下成立）。
 *
 * <h2>两条分支的头处理（刻意不同，避免重复拦截）</h2>
 * <ul>
 *   <li>{@code lb://} ⇒ 用传入的 {@code @LoadBalanced} builder；它上面**已经**挂了
 *       {@code OutboundHeadersInterceptor}（见 {@code LoadBalancedClientConfig}），这里不再加第二个；</li>
 *   <li>{@code http://} ⇒ 普通 builder + **显式加同一个拦截器**，于是"直连排障模式"与"生产模式"
 *       发出的头完全一致（排障时不会因为少一个头而得到 401，把问题指向错误的方向）。</li>
 * </ul>
 */
@Component
public class OutboundRestClientFactory {

    private final String internalToken;

    public OutboundRestClientFactory(
            @org.springframework.beans.factory.annotation.Value("${mall.internal.token:}") String internalToken) {
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
