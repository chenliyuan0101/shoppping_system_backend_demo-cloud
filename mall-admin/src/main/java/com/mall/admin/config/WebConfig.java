package com.mall.admin.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册管理端鉴权拦截器。
 *
 * <p><b>放行规则与单体逐字相同</b>（{@code com.mall.demo.app.WebMvcConfig}）：
 * <pre>
 *   addPathPatterns("/api/admin/**")
 *   excludePathPatterns("/api/admin/auth/login")
 * </pre>
 * ⚠️ {@code /api/admin/auth/me} 与 {@code /logout} **不在**放行名单里（它们要求登录态），
 * 这正是"未登录时 me/logout 也返回 401「未登录」"的来源。
 * ⚠️ 尾斜杠形式（{@code /api/admin/auth/login/}）不在放行名单里（Spring 6+ 不做尾斜杠匹配）——
 * 该形状今天经单体是 404、经网关过滤器是 401「未登录」（P6-6 §十.5② 已记账），本服务不改这个口径。
 *
 * <p>本批**没有** {@code /internal/**} 接口（管理端 BFF 暂时不对外提供内部契约），
 * 因此这里不注册内部鉴权拦截器 —— 少一层没人用的拦截器，也少一处将来会被误认为"已经保护了"的东西。
 * 等 P7 后半真的加了 {@code /internal/**}（看板聚合/会员补数）再按 user-center 的做法补上。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    /** 管理端放行路径（逐字对应单体 excludePathPatterns） */
    public static final String LOGIN_PATH = "/api/admin/auth/login";

    private final AdminAuthInterceptor adminAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/admin/**")
                .excludePathPatterns(LOGIN_PATH);
    }
}
