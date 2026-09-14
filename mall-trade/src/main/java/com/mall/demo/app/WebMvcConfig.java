package com.mall.demo.app;

import com.mall.demo.admin.support.AdminAuthInterceptor;
import com.mall.demo.auth.support.MemberIdArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web MVC 配置：注册管理端鉴权拦截器 + 内部接口鉴权拦截器 + 认证参数解析器。
 * {@code /api/admin/**} 一律要求**网关注入的管理端身份**（P8-2a：不验签、不查账号表）；
 * {@code /internal/**} 一律要求内部共享密钥（且网关不路由该前缀）。
 *
 * <p>⚠️ P8-2a：原来的 {@code excludePathPatterns("/api/admin/auth/login")} 已删除。
 * 它是为"单体自己处理后台登录"留的放行口，而 P7 之后 {@code /api/admin/auth/**}
 * 已由网关**直路由到 {@code mall-admin}**：单体侧的登录/me/登出三个端点连同服务、实体、mapper
 * 一起删掉了（见 {@code admin} 包），因此这条放行在单体里已经**没有任何对应端点**——
 * 留着一个"谁都能匿名打进来"的口子，只会是下一次误匹配的隐患。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;
    private final InternalApiAuthInterceptor internalApiAuthInterceptor;
    private final MemberIdArgumentResolver memberIdArgumentResolver;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // /api/admin/** 全拦，**没有例外路径**：单体不再有后台登录端点（P8-2a，见类注释）
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/admin/**");
        // 内部接口：服务间调用专用，没有例外路径
        registry.addInterceptor(internalApiAuthInterceptor)
                .addPathPatterns("/internal/**");
    }

    /** 自定义参数解析器：处理 {@code @MemberId} / {@code @AuthToken}（见各自 javadoc） */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(memberIdArgumentResolver);
    }
}
