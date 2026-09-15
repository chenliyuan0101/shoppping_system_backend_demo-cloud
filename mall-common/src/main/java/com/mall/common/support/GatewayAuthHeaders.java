package com.mall.common.support;

/**
 * 网关注入的身份头名（**共享内核版**：v5.2 起由 `mall-common` 提供）。
 *
 * <p>这些头**只有网关可以写**：网关的 `MemberIdentityFilter` / `AdminIdentityFilter`
 * 会先**剥离**客户端伪造的 {@code X-Gateway-Auth} 与全部 {@code X-Member-*}/{@code X-Admin-*}，
 * 然后注入下面这几个。名字集中在这里，因为它们是"网关 ↔ 各服务"的隐式契约：
 * 谁写错一个字母，表现为"全站未登录 / 管理端全体未登录"，而且**不会有编译错误**。
 *
 * <p>常量集是原来两份副本的**并集**：会员侧（`X-Member-Id`/`X-Member-Ver`）与管理端
 * （`X-Admin-Id`/`X-Admin-Ver`）；{@link #GATEWAY_AUTH} 两侧共用同一把
 * {@code mall.gateway.auth-token}。各服务只读自己需要的那几个。
 *
 * <p>⚠️ 三件套必须**一起校验**：先过 {@link #GATEWAY_AUTH}（共享密钥、常量时间比较），
 * 之后 {@link #MEMBER_ID}/{@link #ADMIN_ID} 才可被当作身份——否则任何人手写一个
 * {@code X-Member-Id: 1} 就能冒充会员。
 */
public final class GatewayAuthHeaders {

    /** 网关注入的身份凭据（共享密钥，会员侧与管理端同一把 {@code mall.gateway.auth-token}） */
    public static final String GATEWAY_AUTH = "X-Gateway-Auth";

    /** 会员 id（= JWT 的 {@code sub}；仅当 {@link #GATEWAY_AUTH} 校验通过时才可信任） */
    public static final String MEMBER_ID = "X-Member-Id";

    /**
     * 会员令牌版本号（= JWT 的 {@code ver}，**网关已比对过**）。
     *
     * <p>部分服务在网关身份模式下**仍然复核一次**（双保险）：网关的版本读失败是 fail-open 的，
     * 多一道本地比对不会把好人挡在门外，却能挡住"网关 Redis 抖动窗口里拿着已登出令牌"的请求。
     */
    public static final String MEMBER_VER = "X-Member-Ver";

    /** 管理员 id（= JWT 的 {@code sub}；仅当 {@link #GATEWAY_AUTH} 校验通过时才可信任） */
    public static final String ADMIN_ID = "X-Admin-Id";

    /** 管理员令牌版本号（= JWT 的 {@code ver}，语义同 {@link #MEMBER_VER}） */
    public static final String ADMIN_VER = "X-Admin-Ver";

    private GatewayAuthHeaders() {
    }
}
