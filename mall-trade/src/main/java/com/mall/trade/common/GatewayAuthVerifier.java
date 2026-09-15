package com.mall.trade.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.mall.common.support.GatewayAuthHeaders;

/**
 * 校验"这个请求确实来自我们的网关"。
 *
 * <p>P3 起网关会注入 {@link GatewayAuthHeaders#MEMBER_ID}（§4.4 ①）。下游必须能分辨
 * **"网关注入的身份"** 与 **"客户端自己写的头"**——否则任何人手写
 * {@code X-Member-Id: 1} 就能冒充别人。判据就是本类：
 * 请求头 {@link GatewayAuthHeaders#GATEWAY_AUTH} 的值 == 配置的共享密钥。
 *
 * <p>三条纪律（与 P1 内部接口密钥同一套口径）：
 * <ol>
 *   <li><b>常量时间比较</b>（{@link MessageDigest#isEqual}）：避免按字节提前返回泄漏"前缀猜对了几个字符"；</li>
 *   <li><b>未配置密钥 = 谁都不信</b>（fail-closed）：宁可让请求回落到"自己验签"的老路，
 *       也不能因为漏配就把身份头当成可信——那是静默的越权；</li>
 *   <li><b>不区分"没带头"和"带错头"</b>：两者都是"不可信"，调用方只需拿到 true/false。</li>
 * </ol>
 *
 * <p>网关与各服务共用同一把密钥（{@code mall.gateway.auth-token}，
 * 生产走 {@code MALL_GATEWAY_AUTH_TOKEN}）；轮换时要网关与全部服务同时发布。
 */
@Component
public class GatewayAuthVerifier {

    private final byte[] expected;

    public GatewayAuthVerifier(@Value("${mall.gateway.auth-token:}") String token) {
        this.expected = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    /** 该请求是否携带可信的网关注入身份 */
    public boolean isTrusted(HttpServletRequest request) {
        if (expected.length == 0) {
            return false;
        }
        String provided = request.getHeader(GatewayAuthHeaders.GATEWAY_AUTH);
        if (!StringUtils.hasText(provided)) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected);
    }

    /** 密钥是否已配置（排查用：未配置时登录态只能靠各服务自己验签） */
    public boolean configured() {
        return expected.length > 0;
    }
}
