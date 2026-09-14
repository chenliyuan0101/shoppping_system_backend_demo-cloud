package com.mall.admin.config;

import com.mall.admin.domain.AdminUser;
import com.mall.admin.support.AuthAttribute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端接口鉴权拦截器：拦截 {@code /api/admin/**}（**只放行** {@code /api/admin/auth/login}）。
 *
 * <p>与单体 {@code com.mall.demo.admin.support.AdminAuthInterceptor} 的关系：
 * 拦截范围与放行规则**逐字相同**（{@code addPathPatterns("/api/admin/**")} +
 * {@code excludePathPatterns("/api/admin/auth/login")}，见 {@link WebConfig}）；
 * 差别只在"身份从哪来"——单体恒为"自己验签 + 查 sys_user"，本服务由
 * {@link AdminIdentityResolver} 决定（优先网关注入的头，缺失时回落本地验签）。
 * 校验通过后把管理员 id 写进请求属性 {@link AuthAttribute#ADMIN_USER_ID}，
 * 失败抛 401/403，由 {@code GlobalExceptionHandler} 转成统一响应体。
 *
 * <p>⚠️ 本类**不再自行验签**（那是 {@link AdminIdentityResolver}/{@code AdminSession} 的事）：
 * 拦截器里再写一遍校验就会长出第二套口径 —— P7 §2 反复强调"管理端登录态只在一处判定"。
 */
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final AdminIdentityResolver adminIdentityResolver;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AdminUser admin = adminIdentityResolver.resolve(request);
        request.setAttribute(AuthAttribute.ADMIN_USER_ID, admin.getId());
        return true;
    }
}
