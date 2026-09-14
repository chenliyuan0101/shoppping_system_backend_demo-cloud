package com.mall.search.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置（检索服务）：只挂内部接口鉴权。
 *
 * <p>⚠️ 与 mall-marketing / mall-product 的 WebConfig 相比，这里**没有** {@code @MemberId} 参数解析器
 * 与 {@code GatewayIdentityResolver}：本服务的端点**全部**是 {@code /internal/**}
 * （规格 §0："对外只暴露 /internal/v1/**"），而内部端点的身份是调用方在服务端上下文里
 * 用 {@code X-Internal-Token} 证明的，**不读网关注入的身份头**。
 * 按依赖纪律（按编译驱动）就没搬那两个类——搬过来只会是"看起来在生效、其实没有任何端点用它"的死代码。
 * 如果 P6-4/P6-5 出现"给前端直接调用"的端点，再按那时的需要补上（与其它服务同构的那一套）。
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
