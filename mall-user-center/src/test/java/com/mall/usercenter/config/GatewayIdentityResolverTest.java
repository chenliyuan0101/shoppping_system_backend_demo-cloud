package com.mall.usercenter.config;

import com.mall.usercenter.support.BusinessException;
import com.mall.usercenter.support.GatewayAuthHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GatewayIdentityResolver} 单测（无需数据库/Redis）。
 *
 * <p>它替换掉的是单体里的 {@code MemberSessionTest}：单体那套测的是"验签 → typ → 查库 → 状态 → 令牌版本"
 * 的自行验签路径，而这些**在拆分后全部属于网关**。本服务只剩一件事要守：
 * <b>只信网关给的、凭据正确的身份</b>——写错这里就是任意越权（手写一个 {@code X-Member-Id: 1} 变成别人）。
 */
class GatewayIdentityResolverTest {

    private static final String SECRET = "test-gateway-token";

    /** 正常配置了共享密钥的解析器（生产形态） */
    private final GatewayIdentityResolver resolver = new GatewayIdentityResolver(SECRET);

    /** 未配置密钥的解析器（漏配：必须 fail-closed，谁都不信） */
    private final GatewayIdentityResolver unconfigured = new GatewayIdentityResolver("");

    @Test
    @DisplayName("凭据正确 → 返回网关注入的会员 id")
    void trustedIdentity() {
        MockHttpServletRequest request = request(SECRET, "1001", "3");

        assertThat(resolver.resolve(request)).isEqualTo(1001L);
        assertThat(resolver.configured()).isTrue();
    }

    @Test
    @DisplayName("凭据错误（客户端手写 X-Member-Id）→ 401「未登录」，身份不被信任")
    void forgedIdentity() {
        MockHttpServletRequest request = request("definitely-wrong", "1001", "3");

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401));
    }

    @Test
    @DisplayName("没带凭据（只写 X-Member-Id / 什么都没有）→ 401「未登录」")
    void missingCredentials() {
        MockHttpServletRequest withIdOnly = new MockHttpServletRequest();
        withIdOnly.addHeader(GatewayAuthHeaders.MEMBER_ID, "1001");

        MockHttpServletRequest empty = new MockHttpServletRequest();

        assertThatThrownBy(() -> resolver.resolve(withIdOnly))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录");
        assertThatThrownBy(() -> resolver.resolve(empty))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录");
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录");
    }

    @Test
    @DisplayName("[安全] 未配置 mall.gateway.auth-token → fail-closed：带什么都没用，一律 401")
    void unconfiguredSecretIsFailClosed() {
        assertThat(unconfigured.configured()).isFalse();

        // 连"空凭据"都不认（否则漏配密钥就等于完全开放）
        assertThatThrownBy(() -> unconfigured.resolve(request("", "1001", "0")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录");
        assertThatThrownBy(() -> unconfigured.resolve(request(SECRET, "1001", "0")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录");
    }

    @Test
    @DisplayName("凭据正确但缺少/非法的 X-Member-Id → 401「未登录」")
    void malformedMemberId() {
        assertThatThrownBy(() -> resolver.resolve(request(SECRET, null, "0")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录");
        assertThatThrownBy(() -> resolver.resolve(request(SECRET, "  ", "0")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录");
        assertThatThrownBy(() -> resolver.resolve(request(SECRET, "not-a-number", "0")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录");
    }

    @Test
    @DisplayName("[契约] Authorization 头里的令牌**不再是身份来源**：只有它 → 401「未登录」")
    void tokenIsNotAnIdentitySource() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOjF9.sig");

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录");
    }

    @Test
    @DisplayName("resolveOrNull：不可信时返回 null 而不抛异常（限流切面据此回落 IP 维度）")
    void resolveOrNullNeverThrows() {
        assertThat(resolver.resolveOrNull(request(SECRET, "1001", "0"))).isEqualTo(1001L);
        assertThat(resolver.resolveOrNull(request("wrong", "1001", "0"))).isNull();
        assertThat(resolver.resolveOrNull(new MockHttpServletRequest())).isNull();
        assertThat(resolver.resolveOrNull(null)).isNull();
        assertThat(unconfigured.resolveOrNull(request(SECRET, "1001", "0"))).isNull();
    }

    private static MockHttpServletRequest request(String gatewayAuth, String memberId, String memberVer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (gatewayAuth != null) {
            request.addHeader(GatewayAuthHeaders.GATEWAY_AUTH, gatewayAuth);
        }
        if (memberId != null) {
            request.addHeader(GatewayAuthHeaders.MEMBER_ID, memberId);
        }
        if (memberVer != null) {
            request.addHeader(GatewayAuthHeaders.MEMBER_VER, memberVer);
        }
        return request;
    }
}
