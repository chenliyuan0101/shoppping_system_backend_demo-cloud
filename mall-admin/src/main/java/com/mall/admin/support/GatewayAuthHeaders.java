package com.mall.admin.support;

/**
 * 网关注入的身份头名（P7 §2；与会员侧同一套机制，只是前缀是 admin）。
 *
 * <p>三个头**只有网关可以写**：网关 {@code AdminIdentityFilter} 对 {@code /api/admin/**}
 * 会先剥离客户端伪造的 {@code X-Gateway-Auth} 与全部 {@code X-Admin-*}，然后注入下面三个。
 * 名字集中在这里，因为它们是"网关 ↔ 本服务"的隐式契约：
 * 谁写错一个字母，表现为"管理端全体未登录"，而且不会有编译错误。
 *
 * <p>本服务是这三个头在下游的**唯一消费者**（{@code com.mall.admin.config.AdminIdentityResolver}）：
 * 先校验 {@link #GATEWAY_AUTH}（共享密钥、常量时间比较），只有通过之后 {@link #ADMIN_ID} 才被当作身份
 * —— 否则任何人手写一个 {@code X-Admin-Id: 1} 就能冒充管理员。
 */
public final class GatewayAuthHeaders {

    /** 网关注入的身份凭据（共享密钥，与会员侧同一把 {@code mall.gateway.auth-token}） */
    public static final String GATEWAY_AUTH = "X-Gateway-Auth";

    /** 管理员 id（= JWT 的 {@code sub}；仅当 {@link #GATEWAY_AUTH} 校验通过时才可信任） */
    public static final String ADMIN_ID = "X-Admin-Id";

    /**
     * 令牌版本号（= JWT 的 {@code ver}，**网关已比对过**）。
     *
     * <p>本服务在网关身份模式下**仍然复核一次**（{@code 双保险}，文案与单体逐字相同）：
     * 网关的版本读失败是 fail-open 的，多一道本地比对不会把好人挡在门外，
     * 却能挡住"网关 Redis 抖动窗口里拿着已登出令牌"的请求。
     */
    public static final String ADMIN_VER = "X-Admin-Ver";

    private GatewayAuthHeaders() {
    }
}
