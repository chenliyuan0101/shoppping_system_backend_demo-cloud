package com.mall.product.config;

import com.mall.product.support.BusinessException;
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
 * 身份解析：**本服务不验签，只判断"这个身份是不是网关给的"**（方案 §4.4 ①）。
 *
 * <p>本类逐字沿用 mall-review / mall-user-center / mall-marketing 的做法——这不是复制粘贴的偷懒，
 * 而是刻意的：身份模型必须是**全站同一套**，任何服务自作主张改一点，
 * 就会出现"某个服务能被冒充"的静默漏洞。
 *
 * <p><b>为什么需要它</b>：拆分后的分工是"网关验一次，身份往下传"。网关验完 JWT 签名与
 * Redis 里的令牌版本后，注入 {@link GatewayAuthHeaders#GATEWAY_AUTH}（共享密钥）、
 * {@link GatewayAuthHeaders#MEMBER_ID}、{@link GatewayAuthHeaders#MEMBER_VER} 三个头。
 * 下游必须能分辨**"网关注入的身份"**与**"客户端自己写的头"**——否则任何人手写
 * {@code X-Member-Id: 1} 就能以别人的名义操作。
 *
 * <p>三条纪律：
 * <ol>
 *   <li>常量时间比较（{@link MessageDigest#isEqual}）；</li>
 *   <li>未配置密钥 = 谁都不信（fail-closed）——本服务不持有验签能力，漏配时只能拒绝；</li>
 *   <li>不区分"没带头"与"带错头"：对外都是 401「未登录」。</li>
 * </ol>
 *
 * <p>⚠️ <b>P6-1 的现实（必须说清）</b>：本批的 21 个端点里**没有一条**用到 {@code @MemberId}
 * （前台 4 条是游客可访问的读；后台 16 条当前由单体/网关鉴权；内部 7 条走
 * {@link InternalApiAuthInterceptor} 的 X-Internal-Token）。本类与 {@code @MemberId} 解析器
 * 先与其它服务保持同构，供 P6-3/P6-4 的会员侧端点使用；
 * **"本服务对 {@code /api/admin/**} 不做自己的鉴权"是一个已知边界**，见
 * {@code WebConfig} 的类注释与 P6-1 汇报的"没做到/不确定"清单。
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
     * @throws BusinessException 401「未登录」——凭据缺失/不匹配/密钥未配置/会员 id 不可解析
     */
    public Long resolve(HttpServletRequest request) {
        Long memberId = resolveOrNull(request);
        if (memberId == null) {
            throw new BusinessException(401, MSG_NOT_LOGGED_IN);
        }
        return memberId;
    }

    /** 取当前请求的会员 id；**不可信/不适用时返回 {@code null}**（不抛异常）。 */
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
