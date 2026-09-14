package com.mall.demo.admin.support;

import com.mall.demo.common.AuthAttribute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端接口鉴权拦截器：拦截 {@code /api/admin/**}（**没有例外路径**，P8-2a）。
 *
 * <p>校验逻辑统一委托 {@link AdminIdentityResolver}：**只认网关注入的身份头**
 * （{@code X-Gateway-Auth} + {@code X-Admin-Id} + {@code X-Admin-Ver}），
 * 头缺失/凭据不匹配 ⇒ {@code 401 未登录}，由全局异常处理器转成统一响应体。
 *
 * <p>通过校验后把管理员 id 写进请求属性 {@link AuthAttribute#ADMIN_USER_ID}；
 * 该属性名定义在共享内核而不是本类——否则别的业务域的管理端控制器为了读它就得 import 本类，
 * 凭空造出一条跨域依赖（P0 边界冻结实测抓到过一条这样的 B4）。
 *
 * <h2>P8-2a 改掉了什么（**不要改回去**）</h2>
 * 改造前这里走的是 {@code AdminSession}：自行验签 + 查管理员账号表（存在 / 未禁用）+ 比对令牌版本。
 * 那三项在 P7 之后都由网关负责（验签与版本）+ 网关的状态缓存（禁用），
 * 而"查账号表"正是单体脱离 {@code mall} 库的最后一个障碍 —— 本服务**不再读那张表**。
 * 因此本类现在只做一件事：把网关注入的身份**如实转发**成请求属性。
 */
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final AdminIdentityResolver adminIdentityResolver;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AdminIdentityResolver.AdminIdentity admin = adminIdentityResolver.requireAdmin(request);
        request.setAttribute(AuthAttribute.ADMIN_USER_ID, admin.adminId());
        return true;
    }
}
