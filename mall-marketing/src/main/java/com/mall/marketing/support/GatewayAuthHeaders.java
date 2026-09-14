package com.mall.marketing.support;

/**
 * 网关注入的身份头名（方案 §4.4 ①）。与单体 / user-center / content / review 逐字相同。
 *
 * <p>三个头**只有网关可以写**，客户端伪造的同名头会被网关先剥离
 * （{@code mall-gateway} 的 {@code MemberIdentityFilter} 一进来就 remove 掉这三个名字）。
 * 名字集中在这里，是因为它们是"网关 ↔ 各服务"的隐式契约：
 * 谁写错一个字母，表现为"所有人都变成未登录"，而且不会有编译错误。
 *
 * <p>营销域里这三个头管的是**会员侧公开端点**（{@code /api/coupon/available}、
 * {@code /api/coupon/{templateId}/receive}、{@code /api/coupon/mine}）；
 * {@code /internal/**} 走的是另一把钥匙 {@code X-Internal-Token}（那些端点的 {@code memberId}
 * 由调用方 trade 在服务端上下文里传），两条链路不能混
 * （详见 {@code InternalApiAuthInterceptor} 的类注释）。
 */
public final class GatewayAuthHeaders {

    /**
     * 网关注入的身份凭据（共享密钥）。
     *
     * <p>下游据此判断"这个 {@code X-Member-Id} 是网关给的，不是客户端手写的"。
     * 没有它就等于把"我是谁"的决定权交给客户端——券是钱，这是整个改造里最危险的一处，
     * 因此校验必须**常量时间比较**且**未配置密钥时 fail-closed**。
     */
    public static final String GATEWAY_AUTH = "X-Gateway-Auth";

    /** 会员 id（仅当 {@link #GATEWAY_AUTH} 校验通过时才可信任） */
    public static final String MEMBER_ID = "X-Member-Id";

    /**
     * 令牌版本号（网关已比对过）。
     *
     * <p>本服务**不再复核**它：登录态的唯一验证方是网关（§4.4 ①），
     * 本服务只消费"已经确定了的身份"。该常量保留是为了让头名契约完整。
     */
    public static final String MEMBER_VER = "X-Member-Ver";

    private GatewayAuthHeaders() {
    }
}
