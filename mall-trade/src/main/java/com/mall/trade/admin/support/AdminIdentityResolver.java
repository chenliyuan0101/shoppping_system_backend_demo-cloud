package com.mall.trade.admin.support;

import com.mall.trade.common.BusinessException;
import com.mall.common.support.GatewayAuthHeaders;
import com.mall.trade.common.GatewayAuthVerifier;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 管理端身份解析：**只认网关注入的身份头**（P8-2a；方案 §4.4.1「网关是唯一信任锚」）。
 *
 * <h2>本进程在这条链路上做什么（就两件事）</h2>
 * <ol>
 *   <li>请求头 {@link GatewayAuthHeaders#GATEWAY_AUTH} 必须等于共享密钥
 *       {@code mall.gateway.auth-token}（**常量时间比较**，见 {@link GatewayAuthVerifier}）
 *       —— 这是"这个身份是网关给的，不是客户端手写的"的唯一判据；</li>
 *   <li>{@link GatewayAuthHeaders#ADMIN_ID} 必须能解析成正整数
 *       （{@link GatewayAuthHeaders#ADMIN_VER} 只记录、不判定：版本比对在网关）。</li>
 * </ol>
 * 任一不满足（含"密钥未配置"）⇒ {@code 401 未登录} —— 与网关、与
 * {@code mall-admin}/{@code mall-product}/{@code mall-user-center} 等服务的文案逐字一致。
 *
 * <h2>本进程**不再**做什么（P8-2a 的实质，别把它改回去）</h2>
 * <ul>
 *   <li><b>不再自行验签</b>（不看 {@code Authorization}、不解析 JWT）：
 *       管理端 JWT 的签名/有效期/{@code typ=admin}/令牌版本全部由网关判定
 *       （{@code AdminIdentityFilter}，P7 已上线并验证）；</li>
 *   <li><b>不再读管理员账号表</b>（那属于 {@code mall-admin} 的 {@code mall_admin} 库）：
 *       "管理员是否存在/是否被禁用"由网关读状态缓存产出
 *       （{@code 403 账号已被禁用}），本服务不查库、不读别人的表；
 *   </li>
 *   <li><b>不再有本地登录</b>：{@code /api/admin/auth/**} 已由网关直路由到 {@code mall-admin}，
 *       本服务连"放行 login"的排除路径都不再需要（见 {@code app.WebMvcConfig}）。</li>
 * </ul>
 * <p>因此"把单体从 {@code mall} 库摘出来"（P8-2 的前提）在本类之后成立：
 * 本进程的后台端点不再需要任何一张认证域的表。
 *
 * <p>⚠️ 与 {@code mall-admin} 的 {@code AdminIdentityResolver} 的差别（刻意保留）：
 * 那边还回落本地验签（它自己就是认证域、拥有账号表与令牌版本），而本服务是**纯消费者**，
 * 没有账号表可查 ⇒ 身份头缺失时只能 401，不能"自己验一遍"。
 */
@Slf4j
@Component
public class AdminIdentityResolver {

    /** 与网关 {@code AdminIdentityFilter}、与其它下游服务逐字一致的文案（C1：对外错误表现不变） */
    private static final String MSG_NOT_LOGGED_IN = "未登录";

    private final GatewayAuthVerifier gatewayAuthVerifier;

    public AdminIdentityResolver(GatewayAuthVerifier gatewayAuthVerifier) {
        this.gatewayAuthVerifier = gatewayAuthVerifier;
        if (!gatewayAuthVerifier.configured()) {
            // 不阻止启动：此时 /api/admin/** 全部 401（fail-closed）。
            // 与"忘了配置就完全放开"相反 —— 身份来自网关，本服务没有第二条路可走。
            log.warn("mall.gateway.auth-token 未配置：本服务不信任任何网关注入的管理端身份，/api/admin/** 将全部返回 401");
        }
    }

    /**
     * 取当前请求的管理员身份（网关注入的三件套）。
     *
     * @throws BusinessException 401「未登录」—— 凭据缺失/不匹配、密钥未配置、管理员 id 不可解析
     */
    public AdminIdentity requireAdmin(HttpServletRequest request) {
        if (!gatewayAuthVerifier.isTrusted(request)) {
            // 「没带头」与「带错头」对外不区分（不泄漏"密钥猜对了几分"）
            throw new BusinessException(401, MSG_NOT_LOGGED_IN);
        }
        Long adminId = parseLong(request.getHeader(GatewayAuthHeaders.ADMIN_ID));
        if (adminId == null) {
            // 有凭据但没有可用的管理员 id：等同"没带身份"（不可信），不是"登录已失效"
            throw new BusinessException(401, MSG_NOT_LOGGED_IN);
        }
        long ver = parseVer(request.getHeader(GatewayAuthHeaders.ADMIN_VER));
        if (log.isDebugEnabled()) {
            // 快路径的"证据日志"：排障时能一眼看出"这次请求的身份来自网关"
            log.debug("管理端身份来自网关: adminId={} ver={}", adminId, ver);
        }
        return new AdminIdentity(adminId, ver);
    }

    /**
     * 网关注入的管理端身份。
     *
     * @param adminId      管理员 id（= JWT 的 {@code sub}）——写入 {@code AuthAttribute.ADMIN_USER_ID}
     * @param tokenVersion 令牌版本号（网关已比对过；本服务只记录，供日志/后续需要时使用）
     */
    public record AdminIdentity(long adminId, long tokenVersion) {
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;   // id 不是数字 → 与"没带"同样处理（都不可信）
        }
    }

    private static long parseVer(String raw) {
        Long v = parseLong(raw);
        return v == null ? 0L : v;   // 版本缺失按 0（与 JwtUtil 缺 ver 时同口径）
    }
}
