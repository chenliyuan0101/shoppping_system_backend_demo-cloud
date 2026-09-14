package com.mall.search.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 出站调用配置：走服务发现的 {@link RestClient.Builder}。
 *
 * <p>用途：向 {@code mall-product} 拉"索引文档所需的字段"
 * （{@code POST /internal/v1/product/index-docs}）——本服务**没有自己的库**，
 * 索引文档的内容只能从 product 来（规格 §3）。
 *
 * <p>⚠️ {@code @LoadBalanced} 必须标在 {@code @Bean} 方法上——Spring Cloud 的
 * {@code LoadBalancerBeanPostProcessor} 认的是 **bean 定义**上的注解，不是注入点上的注解；
 * 只写在注入点上会变成"按 LoadBalanced 限定符找 bean"，而那个 bean 不存在 → 启动失败
 * （P2 在单体的 {@code OutboundClientConfig} 里实测踩过，注释也记在那里）。
 */
@Configuration
public class LoadBalancedClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder(
        @org.springframework.beans.factory.annotation.Value("${mall.internal.token:}") String internalToken) {
    // P8-4：所有走服务发现的出站调用统一带 X-Internal-Token + 透传 traceId
    return RestClient.builder().requestInterceptor(new OutboundHeadersInterceptor(internalToken));
    }
}
