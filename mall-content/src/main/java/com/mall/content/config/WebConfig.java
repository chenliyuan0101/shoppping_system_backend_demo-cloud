package com.mall.content.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：注册内部接口鉴权拦截器。
 *
 * <p>与单体的差别（P2 决策 1）：**本服务没有登录态拦截器**。
 * 内容域的写接口在 P2 期间由单体做鉴权（`AdminAuthInterceptor` / `@MemberId`），
 * 本服务只接受带 {@code X-Internal-Token} 的内部调用；等 P3 网关验签落地后，
 * 这些内部端点才会升级成对外端点（见方案 §5 P2 决策 1 的"退场条件"）。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final InternalApiAuthInterceptor internalApiAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalApiAuthInterceptor)
                .addPathPatterns("/internal/**");
    }
}
