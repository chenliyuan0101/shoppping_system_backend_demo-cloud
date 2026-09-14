package com.mall.usercenter.service;

import com.mall.usercenter.dto.AuthResponse;
import com.mall.usercenter.dto.LoginRequest;
import com.mall.usercenter.dto.PasswordRequest;
import com.mall.usercenter.dto.RegisterRequest;
import com.mall.usercenter.dto.UserInfo;

/**
 * 前台会员认证服务(对应《接口文档.md》2.1)。
 *
 * <p><b>P3 起不再收 token，只收会员 id</b>（§4.4 ①）：登录态由网关验签并注入身份，
 * 服务侧只消费身份。这样"令牌长什么样、怎么校验"就只有一处实现（网关），
 * 而本服务不必持有验签密钥与验签逻辑——这正是用户中心能独立部署的前提。
 */
public interface MemberService {

    /** 注册(成功后自动登录，返回 token) */
    AuthResponse register(RegisterRequest request);

    /** 登录：account 兼容用户名/手机号 */
    AuthResponse login(LoginRequest request);

    /**
     * 退出：令牌版本号 +1，该会员所有旧 token 立即失效（网关比对版本号后直接 401）。
     *
     * <p>不需要 token 本身——"当前是谁"已由 {@code @MemberId} 参数解析器确定（网关注入身份）。
     */
    void logout(long memberId);

    /** 当前用户信息 */
    UserInfo getUserInfo(long memberId);

    /** 修改密码（改后旧 token 立即失效） */
    void changePassword(long memberId, PasswordRequest request);
}
