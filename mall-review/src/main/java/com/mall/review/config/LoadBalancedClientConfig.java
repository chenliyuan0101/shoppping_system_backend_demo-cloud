package com.mall.review.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import com.mall.common.client.OutboundHeadersInterceptor;

/**
 * 出站调用配置：走服务发现的 {@link RestClient.Builder}。
 *
 * <p>{@code @LoadBalanced} 必须标在 {@code @Bean} 方法上——Spring Cloud 的
 * {@code LoadBalancerBeanPostProcessor} 认的是 **bean 定义**上的注解，不是注入点上的注解。
 * 因此这里单独提供一个 builder，与 Spring Boot 自带的 {@code RestClient.Builder} 并存；
 * 需要 {@code lb://} 的调用方用它（注入口写 {@code @LoadBalanced} 限定即可）。
 *
 * <p>P4 的用途：评价展示取"会员当前昵称"做兜底（快照为空时）、提交评价时校验商品是否在架。
 * ⚠️ 但方案 §4.5 的方向是**尽量不调用**——昵称走快照、待评价校验走本地读模型，
 * 远程调用只作为"快照缺失"的兜底路径，不能成为主链路。
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
