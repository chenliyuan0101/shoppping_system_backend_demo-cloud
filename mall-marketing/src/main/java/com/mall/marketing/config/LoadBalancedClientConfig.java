package com.mall.marketing.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import com.mall.common.client.OutboundHeadersInterceptor;

/**
 * 出站调用配置：走服务发现的 {@link RestClient.Builder}（P5 步骤 C 起需要）。
 *
 * <p>用途：后台"领取记录"调 user-center 的 {@code /internal/v1/user/member/batch} 取会员用户名/昵称。
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
