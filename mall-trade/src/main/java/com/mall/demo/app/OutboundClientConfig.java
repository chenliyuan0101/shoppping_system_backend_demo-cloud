package com.mall.demo.app;

import com.mall.demo.common.client.OutboundHeadersInterceptor;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 出站调用配置（P2 起单体也需要）。
 *
 * <p>为什么必须显式提供一个 {@code @LoadBalanced} 的 builder：Spring Cloud 的
 * {@code LoadBalancerBeanPostProcessor} 只认 **bean 定义**上的 {@code @LoadBalanced}，
 * 而 {@code @LoadBalanced} 同时是 {@code @Qualifier}——所以"在哪写这个注解"决定了两个结果：
 * <ul>
 *   <li>写在这里（{@code @Bean} 方法上）→ 得到一个会解析 {@code lb://服务名} 的 builder；</li>
 *   <li>只写在注入点上 → 变成"按 LoadBalanced 限定符找 bean"，而这个 bean 不存在 →
 *       启动即报 {@code No qualifying bean of type 'RestClient$Builder'}（P2 实测踩过）。</li>
 * </ul>
 * 与 Spring Boot 自带的普通 {@code RestClient.Builder} 并存：只有需要服务发现的调用方
 * （{@link com.mall.demo.common.client.ContentInternalClient}）用带限定符的这一个。
 */
@Configuration
public class OutboundClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder(
        @org.springframework.beans.factory.annotation.Value("${mall.internal.token:}") String internalToken) {
    // P8-4：所有走服务发现的出站调用统一带 X-Internal-Token + 透传 traceId
    return RestClient.builder().requestInterceptor(new OutboundHeadersInterceptor(internalToken));
    }
}
