package com.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.admin.config.AdminIdentityResolver;
import com.mall.admin.domain.AdminUser;
import com.mall.admin.dto.AdminLoginRequest;
import com.mall.admin.dto.AdminLoginResponse;
import com.mall.admin.mapper.AdminUserMapper;
import com.mall.admin.service.AdminAuthService;
import com.mall.admin.support.AdminStatusCache;
import com.mall.admin.support.BusinessException;
import com.mall.admin.support.JwtUtil;
import com.mall.admin.support.TokenVersionService;
import com.mall.admin.support.constant.EnableStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 后台登录：校验 {@code sys_user}（BCrypt），签发 {@code typ=admin} 的 Token，登录即全权限。
 *
 * <p><b>与单体逐字相同</b>（{@code com.mall.demo.admin.service.impl.AdminAuthServiceImpl}）：
 * <ol>
 *   <li>账号或密码为空 → {@code 400 请输入账号和密码}（{@link StringUtils#hasText} 判空，用户名 {@code trim()} 后查询）；</li>
 *   <li>查不到/BCrypt 不匹配 → {@code 401 用户名或密码错误}（**不区分**"用户不存在"与"密码错"，避免账号枚举）；</li>
 *   <li>{@code status != 1} → {@code 403 账号已被禁用}（**顺序在口令校验之后**，与单体一致）；</li>
 *   <li>签发 {@code token = createToken(id, username, "admin", tokenVersionService.current("admin", id))}
 *       —— 版本号取当前值（登出/禁用过就 >0），并返回 {@code {id,username,nickname,status}}。</li>
 * </ol>
 *
 * <p><b>唯一的增量：登录成功顺手写一次状态缓存</b>（P7 §2.5）——
 * {@code mall:cache:admin:status:{id} = {"adminId":..,"username":..,"status":1}}。
 * 为什么登录要写：这是"网关手里唯一那份管理员状态"的**正常路径来源**；
 * 没有它，路由切换后网关读到的是"键不存在"（fail-open），而 {@code 403 账号已被禁用}
 * 就只能靠对账任务在 10s 内补上。
 * ⚠️ 写缓存**失败不影响登录**（{@link AdminStatusCache} → {@code CacheService} 全链路 fail-open）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthServiceImpl implements AdminAuthService {

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenVersionService tokenVersionService;
    /** 身份解析（网关注入的头优先；过渡态回落本地验签）——管理端登录态只在这条链上判定 */
    private final AdminIdentityResolver adminIdentityResolver;
    /** P7 §2.5：本服务是管理端状态缓存的唯一写入方 */
    private final AdminStatusCache adminStatusCache;

    @Override
    public AdminLoginResponse login(AdminLoginRequest request) {
        if (!StringUtils.hasText(request.getUsername()) || !StringUtils.hasText(request.getPassword())) {
            throw new BusinessException(400, "请输入账号和密码");
        }
        AdminUser admin = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, request.getUsername().trim()));
        if (admin == null || !passwordEncoder.matches(request.getPassword(), admin.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (admin.getStatus() == null || admin.getStatus() != EnableStatus.ENABLED) {
            throw new BusinessException(403, "账号已被禁用");
        }
        // P7 §2.5：登录即把"启用"写进状态缓存（网关据此判定；写失败不影响登录）
        adminStatusCache.putEnabled(admin.getId(), admin.getUsername());
        return new AdminLoginResponse(jwtUtil.createToken(admin.getId(), admin.getUsername(), JwtUtil.TYPE_ADMIN,
                tokenVersionService.current(TokenVersionService.TYPE_ADMIN, admin.getId())),
                toInfo(admin));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminLoginResponse.AdminInfo getAdminInfo() {
        // 校验口径统一在 AdminIdentityResolver/AdminSession（管理员是否存在 + status + 令牌版本号 + 请求级缓存）
        return toInfo(currentAdmin());
    }

    @Override
    public void logout() {
        AdminUser admin = currentAdmin();
        // 主动失效：版本号 +1，旧 token 立即不可用（与单体 logout 的语义一致：只 bump，不动状态缓存）
        long ver = tokenVersionService.bump(TokenVersionService.TYPE_ADMIN, admin.getId());
        log.info("管理员登出，令牌版本号已提升: adminId={} ver={}", admin.getId(), ver);
    }

    /** 当前请求的管理员（拦截器已校验过一次，这里走请求级缓存，不重复查库/读 Redis） */
    private AdminUser currentAdmin() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attrs == null ? null : attrs.getRequest();
        if (request == null) {
            // 只可能出现在"没有 HTTP 上下文却调用登录态接口"的场景（例如误当内部方法调用）
            throw new BusinessException(401, "未登录");
        }
        return adminIdentityResolver.resolve(request);
    }

    private AdminLoginResponse.AdminInfo toInfo(AdminUser admin) {
        return AdminLoginResponse.AdminInfo.builder()
                .id(admin.getId())
                .username(admin.getUsername())
                .nickname(admin.getNickname())
                .status(admin.getStatus())
                .build();
    }
}
