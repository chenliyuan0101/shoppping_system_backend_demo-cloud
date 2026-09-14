package com.mall.admin.service;

import com.mall.admin.dto.AdminLoginRequest;
import com.mall.admin.dto.AdminLoginResponse;

/**
 * 管理端登录态服务（{@code /api/admin/auth/**}）。
 *
 * <p>对外行为（状态码 + 文案 + 响应字段）与单体 {@code com.mall.demo.admin.service.AdminAuthService}
 * **逐字相同**（C1）；实现里唯一的增量是"登录成功顺手写一次状态缓存"（P7 §2.5，见实现类注释）。
 */
public interface AdminAuthService {

    /**
     * 后台登录：校验 {@code sys_user}（BCrypt）→ 签发 {@code typ=admin} 令牌 → 写状态缓存。
     *
     * @throws com.mall.admin.support.BusinessException 400「请输入账号和密码」/ 401「用户名或密码错误」/ 403「账号已被禁用」
     */
    AdminLoginResponse login(AdminLoginRequest request);

    /**
     * 当前管理员信息（{@code /api/admin/auth/me}）。
     *
     * <p>身份由拦截器/解析器确定（网关注入的头，或过渡态的本地验签），此处只做"取出来 + 转 DTO"。
     *
     * @throws com.mall.admin.support.BusinessException 401/403（文案见类注释）
     */
    AdminLoginResponse.AdminInfo getAdminInfo();

    /**
     * 登出：令牌版本号 +1，旧令牌立即失效（无状态 JWT 的主动失效）。
     *
     * <p>⚠️ 与单体一样，**这不影响状态缓存**：登录态作废靠版本号，账号状态靠状态缓存，两件事互不替代。
     *
     * @throws com.mall.admin.support.BusinessException 401/403（身份校验失败）
     */
    void logout();
}
