package com.mall.admin.support;

import com.mall.admin.domain.AdminUser;
import com.mall.admin.mapper.AdminUserMapper;
import com.mall.admin.support.constant.EnableStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 管理端登录态的**唯一校验实现**（逐字平移单体 {@code com.mall.demo.admin.support.AdminSession}）。
 *
 * <p>单体里的校验口径（顺序**不许变**）：
 * <pre>
 *   ① 验签 + 有效期（{@link JwtUtil#parse}）        → 401 登录已失效，请重新登录
 *   ② typ 必须是 admin                              → 401 登录已失效，请使用管理员账号登录
 *   ③ 管理员必须还在（{@code sys_user} 查得到）      → 401 登录已失效，请重新登录
 *   ④ status 必须是 1（{@code EnableStatus.ENABLED}）→ 403 账号已被禁用
 *   ⑤ 令牌版本号一致（{@link TokenVersionService}）  → 401 登录已失效，请重新登录
 * </pre>
 * ④ 在 ⑤ 之前是**有意的**：顺序反了会把"已禁用 + 版本已 bump"的管理员从 403 变成 401（文案漂移）。
 * 网关 {@code AdminIdentityFilter} 用同一顺序（该侧有专门的用例 `statusCheckRunsBeforeVersionCheck` 钉住）。
 *
 * <h2>P7 §2 的两条入口</h2>
 * <ul>
 *   <li>{@link #requireLocalToken(String)}：**过渡态**入口 —— 服务端自己验签（①②）+ 查库（③④⑤）。
 *       直连 8108 带真实管理员令牌时走这条路，行为与今天的单体**逐字相同**（C1）；</li>
 *   <li>{@link #requireAdminById(Long, long)}：**网关身份**入口 —— ①②已由网关完成，
 *       本服务只做 ③④⑤（其中 ⑤ 是"双保险"的复核：网关的版本读失败是 fail-open 的）。</li>
 * </ul>
 * 两条入口共用同一段 ③④⑤ ⇒ 不可能出现"网关路径比本地路径宽松"这种偏差。
 *
 * <p>请求级缓存（与单体同一考虑）：一次请求里拦截器解析一次、{@code /api/admin/auth/me} 再取一次，
 * 不会重复查库/读 Redis。缓存以"解析入口 + 凭据"为键存，避免同一请求里出现不同凭据时被误复用。
 */
@Component
@RequiredArgsConstructor
public class AdminSession {

    /** 请求级属性名：缓存本次请求已解析的管理员 */
    private static final String ATTR_ADMIN = AdminSession.class.getName() + ".resolved";

    private final JwtUtil jwtUtil;
    private final AdminUserMapper adminUserMapper;
    private final TokenVersionService tokenVersionService;

    /**
     * 【过渡态】解析**裸 token**（已剥离 "Bearer "）并完成 ①②③④⑤。
     *
     * @throws BusinessException 401/403（文案见类注释）
     */
    public AdminUser requireLocalToken(String token) {
        AdminUser cached = cached("local:" + token);
        if (cached != null) {
            return cached;
        }
        JwtUtil.Claims claims = jwtUtil.parse(token);
        // 双体系隔离：会员 token 不得用于后台接口(即使 id 恰好与某个管理员相同)
        if (!JwtUtil.TYPE_ADMIN.equals(claims.type())) {
            throw new BusinessException(401, "登录已失效，请使用管理员账号登录");
        }
        return cache("local:" + token, requireAdminById(claims.userId(), claims.ver()));
    }

    /**
     * 【网关身份】按"网关注入的管理员 id + 令牌版本号"完成 ③④⑤（①②由网关负责）。
     *
     * @param adminId  管理员 id（= JWT 的 {@code sub} = {@code X-Admin-Id}）
     * @param tokenVer 令牌版本号（= JWT 的 {@code ver} = {@code X-Admin-Ver}；缺失按 0）
     * @throws BusinessException 401/403（文案见类注释）
     */
    public AdminUser requireAdminById(Long adminId, long tokenVer) {
        String key = "gateway:" + adminId + ":" + tokenVer;
        AdminUser cached = cached(key);
        if (cached != null) {
            return cached;
        }
        AdminUser admin = adminUserMapper.selectById(adminId);
        if (admin == null) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
        if (admin.getStatus() == null || admin.getStatus() != EnableStatus.ENABLED) {
            throw new BusinessException(403, "账号已被禁用");
        }
        if (!tokenVersionService.matches(TokenVersionService.TYPE_ADMIN, admin.getId(), tokenVer)) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
        return cache(key, admin);
    }

    // ==================== 请求级缓存 ====================

    private ServletRequestAttributes currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs ? attrs : null;
    }

    private AdminUser cached(String key) {
        ServletRequestAttributes attrs = currentRequest();
        if (attrs == null) {
            return null;
        }
        Object value = attrs.getAttribute(ATTR_ADMIN, RequestAttributes.SCOPE_REQUEST);
        if (value instanceof Resolved resolved && resolved.key().equals(key)) {
            return resolved.admin();
        }
        return null;
    }

    private AdminUser cache(String key, AdminUser admin) {
        ServletRequestAttributes attrs = currentRequest();
        if (attrs != null) {
            attrs.setAttribute(ATTR_ADMIN, new Resolved(key, admin), RequestAttributes.SCOPE_REQUEST);
        }
        return admin;
    }

    /** 单次请求内已解析的管理员；连同"解析入口+凭据"一起存，避免同一请求里不同凭据被误复用 */
    private record Resolved(String key, AdminUser admin) {
    }
}
