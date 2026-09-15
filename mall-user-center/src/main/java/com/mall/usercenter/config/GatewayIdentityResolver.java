package com.mall.usercenter.config;

import com.mall.usercenter.support.BusinessException;
import com.mall.common.support.GatewayAuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.mall.common.support.MemberId;

/**
 * 身份解析：**本服务不再验签，只判断"这个身份是不是网关给的"**（§4.4 ①）。
 *
 * <p><b>为什么需要它</b>：拆分后的分工是"网关验一次，身份往下传"。网关验完 JWT 签名与
 * Redis 里的令牌版本后，注入 {@link GatewayAuthHeaders#GATEWAY_AUTH}（共享密钥）、
 * {@link GatewayAuthHeaders#MEMBER_ID}、{@link GatewayAuthHeaders#MEMBER_VER} 三个头。
 * 下游必须能分辨**"网关注入的身份"**与**"客户端自己写的头"**——否则任何人手写
 * {@code X-Member-Id: 1} 就能冒充别人。判据就是本类：请求头里的共享密钥 == 配置的密钥。
 *
 * <p><b>三条纪律</b>（与单体 {@code GatewayAuthVerifier}、本服务
 * {@link InternalApiAuthInterceptor} 同一套口径）：
 * <ol>
 *   <li><b>常量时间比较</b>（{@link MessageDigest#isEqual}）：避免按字节提前返回泄漏
 *       "前缀猜对了几个字符"；</li>
 *   <li><b>未配置密钥 = 谁都不信</b>（fail-closed）：漏配 {@code mall.gateway.auth-token}
 *       时宁可让需要登录的接口全部 401，也不能因为漏配就把身份头当成可信——那是静默的越权。
 *       注意这与**单体**不同：单体在网关身份不可信时还能回落到"自己验签"，
 *       而本服务已经不持有验签能力（这正是 §4.4 的目的），所以只能拒绝；</li>
 *   <li><b>不区分"没带头"与"带错头"</b>：两者对外都是 401「未登录」，不泄漏"密钥对了几分"。</li>
 * </ol>
 *
 * <p><b>本类只回答"你是谁"，不回答"你的登录态还作不作数"</b>：令牌版本由网关比对（旧 token 在
 * 网关就被 401 掉），会员是否已被禁用则由会员域自己（{@code MemberServiceImpl.requireMember}）
 * 在读取会员时判定——两件事分属不同层次，混在一个解析器里会让"身份"与"业务状态"互相纠缠。
 *
 * <p>配置项：{@code mall.gateway.auth-token}（dev 明文写在 profile，prod 走
 * {@code MALL_GATEWAY_AUTH_TOKEN}），必须与网关的同一项**逐字相同**；轮换时要网关与全部服务同时发布。
 */
@Slf4j
@Component
public class GatewayIdentityResolver {

    /** 与单体逐字一致的文案（C1：对外错误表现不变） */
    private static final String MSG_NOT_LOGGED_IN = "未登录";

    private final byte[] expected;

    public GatewayIdentityResolver(@Value("${mall.gateway.auth-token:}") String token) {
        this.expected = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (this.expected.length == 0) {
            // 不阻止启动：此时所有需要登录的接口都会 401（fail-closed）。
            // 与"忘了配置就完全开放"相反——身份来自网关，本服务没有第二条路可走。
            log.warn("mall.gateway.auth-token 未配置：本服务不信任任何身份头，需要登录的接口将全部返回 401");
        }
    }

    /**
     * 取当前请求的会员 id。
     *
     * @param request 当前 HTTP 请求
     * @return 会员 id（一定非 null）
     * @throws BusinessException 401「未登录」——凭据缺失/不匹配/密钥未配置/会员 id 不可解析
     */
    public Long resolve(HttpServletRequest request) {
        Long memberId = resolveOrNull(request);
        if (memberId == null) {
            throw new BusinessException(401, MSG_NOT_LOGGED_IN);
        }
        return memberId;
    }

    /**
     * 取当前请求的会员 id；**不可信/不适用时返回 {@code null}**（不抛异常）。
     *
     * <p>供"解析不到就回落别的维度"的调用方使用（限流切面：按会员维度限流时解析不到身份，
     * 就回落到客户端 IP 计数），以及"只想判断有没有可信身份"的场景。
     *
     * @param request 当前 HTTP 请求；可为 null（返回 null）
     * @return 会员 id；请求不可信或会员 id 缺失/非法时为 {@code null}
     */
    public Long resolveOrNull(HttpServletRequest request) {
        if (expected.length == 0 || request == null) {
            return null;   // fail-closed：没配置密钥 = 谁都不信
        }
        String provided = request.getHeader(GatewayAuthHeaders.GATEWAY_AUTH);
        if (!StringUtils.hasText(provided)
                || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected)) {
            return null;
        }
        Long memberId = parseLong(request.getHeader(GatewayAuthHeaders.MEMBER_ID));
        if (memberId != null && log.isDebugEnabled()) {
            // 快路径的"证据日志"：排障时能一眼看出"这次请求的身份来自网关"
            log.debug("网关身份: memberId={} ver={}", memberId, request.getHeader(GatewayAuthHeaders.MEMBER_VER));
        }
        return memberId;
    }

    /** 密钥是否已配置（排查用：未配置时所有需要登录的接口都会 401） */
    public boolean configured() {
        return expected.length > 0;
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;   // 会员 id 不是数字 → 与"没带"同样处理（都不可信）
        }
    }
}
