package com.mall.usercenter.support;

/**
 * 网关注入的身份头名（P3 §4.4 ①）。
 *
 * <p>三个头**只有网关可以写**，客户端伪造的同名头会被网关先剥离
 * （{@code mall-gateway} 的 {@code MemberIdentityFilter} 一进来就 remove 掉这三个名字）。
 * 名字集中在这里，是因为它们是"网关 ↔ 各服务"的隐式契约：
 * 谁写错一个字母，表现为"所有人都变成未登录"，而且不会有编译错误。
 *
 * <p>本服务是这三个头在下游的**唯一消费者**：{@link com.mall.usercenter.config.GatewayIdentityResolver}
 * 先校验 {@link #GATEWAY_AUTH}（共享密钥，常量时间比较、未配置即 fail-closed），
 * 只有通过之后 {@link #MEMBER_ID} 才被当作身份——否则任何人手写一个 {@code X-Member-Id: 1} 就能冒充别人。
 *
 * <p>与单体的 {@code com.mall.demo.common.GatewayAuthHeaders} 逐字相同（三个头名必须一致，
 * 否则网关注入了身份而本服务认不出来）。
 */
public final class GatewayAuthHeaders {

    /**
     * 网关注入的身份凭据（共享密钥）。
     *
     * <p>下游据此判断"这个 {@code X-Member-Id} 是网关给的，不是客户端手写的"。
     * 没有它就等于把"我是谁"的决定权交给客户端——这是整个改造里最危险的一处，
     * 因此校验必须**常量时间比较**且**未配置密钥时 fail-closed**。
     */
    public static final String GATEWAY_AUTH = "X-Gateway-Auth";

    /** 会员 id（仅当 {@link #GATEWAY_AUTH} 校验通过时才可信任） */
    public static final String MEMBER_ID = "X-Member-Id";

    /**
     * 令牌版本号（网关已比对过）。
     *
     * <p>本服务**不再复核**它：登录态的唯一验证方是网关（§4.4 ①），
     * 本服务只消费"已经确定了的身份"。该常量保留是为了让头名契约完整、
     * 便于排查时把三个头一起打印出来。
     */
    public static final String MEMBER_VER = "X-Member-Ver";

    private GatewayAuthHeaders() {
    }
}
