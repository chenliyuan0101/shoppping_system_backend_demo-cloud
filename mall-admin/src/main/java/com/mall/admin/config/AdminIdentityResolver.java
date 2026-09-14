package com.mall.admin.config;

import com.mall.admin.domain.AdminUser;
import com.mall.admin.support.AdminSession;
import com.mall.admin.support.AuthHeader;
import com.mall.admin.support.BusinessException;
import com.mall.admin.support.GatewayAuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理端身份解析：**先认网关注入的身份，认不到再回落到本地验签**（P7 §2 的过渡形态）。
 *
 * <h2>判定阶梯（顺序即安全语义）</h2>
 * <pre>
 * ① 请求里有 X-Gateway-Auth 吗？
 *      有 → 它必须等于 mall.gateway.auth-token（常量时间比较）；不等/未配置 → 401「未登录」
 *           （**不回落**本地验签：带了伪造凭据就是攻击形状，不该因为"恰好还带了个合法 token"而放行）
 *         → 再取 X-Admin-Id（不是数字 → 401「未登录」）与 X-Admin-Ver（缺失按 0）
 *         → 交给 {@link AdminSession#requireAdminById} 完成 ③④⑤（管理员存在 / status=1 / 令牌版本）
 * ② 没有 X-Gateway-Auth：
 *      · mall.admin.auth.require-gateway-identity=true  → 401「未登录」（P7 §2 目标形态，与 user-center 同口径）
 *      · =false（默认，过渡态）                          → 本地验签：AuthHeader.bearer → AdminSession.requireLocalToken
 * </pre>
 *
 * <h2>为什么默认是"双路"而不是直接 fail-closed</h2>
 * 本批**不改网关路由**：`/api/admin/auth/**` 今天仍指向单体，网关的 admin 身份过滤器虽然已上线，
 * 但"路由切到 mall-admin"是后续 P7 的事。此时若本服务只认网关身份，
 * <b>直连 8108 的一切调用都会 401</b>（包括运维排查用的 curl），而单体 8080 的行为是"自己验签"——
 * 两边不一致会让"回退"变得不可预测。所以默认保留与单体**逐字相同**的本地验签路径（C1 的要点），
 * 用 `mall.admin.auth.require-gateway-identity=true` 一键切到目标形态
 * —— 与网关侧 `mall.gateway.admin-auth.enabled` 是同一套"可回退"设计（P7 §7 要求管理端可退）。
 *
 * <h2>三条文案的归属（P7 §2.5 定案，逐字照抄单体）</h2>
 * {@code 401 未登录} = 头缺失/格式错（本类与网关各一处，文案相同）；
 * {@code 401 登录已失效，请重新登录} = 验签/有效期/令牌版本；
 * {@code 403 账号已被禁用} = {@code sys_user.status != 1}（本服务查库；网关侧靠状态缓存，见 P7 §2.5）。
 */
@Slf4j
@Component
public class AdminIdentityResolver {

    /** 与单体 {@code AuthHeader} / 网关 {@code AdminIdentityFilter.MSG_NOT_LOGIN} 逐字一致 */
    private static final String MSG_NOT_LOGGED_IN = "未登录";

    private static final String HEADER_AUTHORIZATION = "Authorization";

    private final AdminSession adminSession;
    private final byte[] expected;
    private final boolean requireGatewayIdentity;

    public AdminIdentityResolver(AdminSession adminSession,
                                 @Value("${mall.gateway.auth-token:}") String gatewayAuthToken,
                                 @Value("${mall.admin.auth.require-gateway-identity:false}") boolean requireGatewayIdentity) {
        this.adminSession = adminSession;
        this.expected = gatewayAuthToken == null ? new byte[0] : gatewayAuthToken.getBytes(StandardCharsets.UTF_8);
        this.requireGatewayIdentity = requireGatewayIdentity;
        if (this.expected.length == 0) {
            // 不阻止启动：此时网关注入的身份一律不认，退化为"本地验签"（默认态）；
            // 若同时开了 require-gateway-identity=true，则所有后台接口 401（fail-closed，不会静默越权）。
            log.warn("mall.gateway.auth-token 未配置：不信任任何网关注入的管理端身份（require-gateway-identity={}）",
                    requireGatewayIdentity);
        }
        log.info("管理端身份解析: require-gateway-identity={}（false=优先网关身份、缺失时回落本地验签/过渡态；"
                + "true=只认网关注入的身份）", requireGatewayIdentity);
    }

    /**
     * 取当前请求的管理员（通过校验）。
     *
     * @throws BusinessException 401「未登录」/ 401「登录已失效，请重新登录」/ 401「登录已失效，请使用管理员账号登录」/ 403「账号已被禁用」
     */
    public AdminUser resolve(HttpServletRequest request) {
        String provided = request == null ? null : request.getHeader(GatewayAuthHeaders.GATEWAY_AUTH);
        if (StringUtils.hasText(provided)) {
            if (!trusted(provided)) {
                // 「没带头」与「带错头」对外都不区分（不泄漏"密钥对了几分"）
                throw new BusinessException(401, MSG_NOT_LOGGED_IN);
            }
            Long adminId = parseLong(request.getHeader(GatewayAuthHeaders.ADMIN_ID));
            if (adminId == null) {
                throw new BusinessException(401, MSG_NOT_LOGGED_IN);
            }
            long ver = parseVer(request.getHeader(GatewayAuthHeaders.ADMIN_VER));
            if (log.isDebugEnabled()) {
                log.debug("管理端身份来自网关: adminId={} ver={}", adminId, ver);
            }
            return adminSession.requireAdminById(adminId, ver);
        }
        if (requireGatewayIdentity) {
            // P7 §2 目标形态：身份只能来自网关（直连端口无身份头 ⇒ 401「未登录」）
            throw new BusinessException(401, MSG_NOT_LOGGED_IN);
        }
        // 过渡态：与单体逐字相同的本地验签（AuthHeader 的 401「未登录」在头缺失/格式错时抛出）
        String token = AuthHeader.bearer(request == null ? null : request.getHeader(HEADER_AUTHORIZATION));
        return adminSession.requireLocalToken(token);
    }

    /** 是否开启了"只认网关身份"（运维/自检用） */
    public boolean requireGatewayIdentity() {
        return requireGatewayIdentity;
    }

    /** 共享凭据是否已配置（未配置时任何 X-Gateway-Auth 都不认） */
    public boolean gatewayTokenConfigured() {
        return expected.length > 0;
    }

    private boolean trusted(String provided) {
        return expected.length > 0
                && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected);
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
