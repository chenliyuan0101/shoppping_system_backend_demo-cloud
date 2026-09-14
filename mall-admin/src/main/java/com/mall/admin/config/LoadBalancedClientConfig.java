package com.mall.admin.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 出站调用配置：走服务发现的 {@link RestClient.Builder}（P7 后半起需要）。
 *
 * <p>用途：看板并行聚合（trade=单体 {@code mall-legacy} / product / user-center）与
 * 后台会员列表（user-center 分页主查 + trade 批量补数）。
 *
 * <p>⚠️ {@code @LoadBalanced} 必须标在 {@code @Bean} 方法上——Spring Cloud 的
 * {@code LoadBalancerBeanPostProcessor} 认的是 **bean 定义**上的注解，不是注入点上的注解；
 * 只写在注入点上会变成"按 LoadBalanced 限定符找 bean"，而那个 bean 不存在 → 启动失败
 * （P2 在单体的 {@code OutboundClientConfig} 里实测踩过，注释也记在那里；本批照抄 mall-marketing 的写法）。
 *
 * <p>pom 里 {@code spring-cloud-starter-loadbalancer} 是本批才加的（P7 前半零出站调用，
 * 刻意不留没人用的依赖）——本条注释与 pom 的注释互相指向，避免"以后有人以为它一直在这儿"。
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
