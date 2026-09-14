package com.mall.product.support;

/**
 * 网关注入的身份头名（P3 §4.4 ①）。
 *
 * <p>三个头**只有网关可以写**，客户端伪造的同名头会被网关先剥离（{@code MemberIdentityFilter}）。
 * 名字集中在这里，是因为它们是"网关 ↔ 各服务"的隐式契约：
 * 谁写错一个字母，表现为"所有人都变成未登录"，而且不会有编译错误。
 */
public final class GatewayAuthHeaders {

    /**
     * 网关注入的身份凭据（共享密钥）。
     *
     * <p>下游据此判断"这个 {@code X-Member-Id} 是网关给的，不是客户端手写的"。
     * 没有它就等于把"我是谁"的决定权交给客户端——这是整个改造里最危险的一处，
     * 因此校验必须**常量时间比较**且**未配置密钥时 fail-closed**（见 {@link GatewayAuthVerifier}）。
     */
    public static final String GATEWAY_AUTH = "X-Gateway-Auth";

    /** 会员 id（仅当 {@link #GATEWAY_AUTH} 校验通过时才可信任） */
    public static final String MEMBER_ID = "X-Member-Id";

    /** 令牌版本号（网关已比对过，下游用于纵深防御地复核一次） */
    public static final String MEMBER_VER = "X-Member-Ver";

    private GatewayAuthHeaders() {
    }
}
