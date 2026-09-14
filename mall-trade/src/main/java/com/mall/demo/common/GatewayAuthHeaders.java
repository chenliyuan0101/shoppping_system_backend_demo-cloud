package com.mall.demo.common;

/**
 * 网关注入的身份头名（P3 §4.4 ① 会员侧 / P7 §2 管理端）。
 *
 * <p>这些头**只有网关可以写**：客户端伪造的同名头会被网关先剥离
 * （会员路径由 {@code MemberIdentityFilter}、{@code /api/admin/**} 由 {@code AdminIdentityFilter}）。
 * 名字集中在这里，是因为它们是"网关 ↔ 各服务"的隐式契约：
 * 谁写错一个字母，表现为"所有人都变成未登录"，而且不会有编译错误。
 */
public final class GatewayAuthHeaders {

    /**
     * 网关注入的身份凭据（共享密钥，会员侧与管理端**同一把**）。
     *
     * <p>下游据此判断"这个 {@code X-Member-Id}/{@code X-Admin-Id} 是网关给的，不是客户端手写的"。
     * 没有它就等于把"我是谁"的决定权交给客户端——这是整个改造里最危险的一处，
     * 因此校验必须**常量时间比较**且**未配置密钥时 fail-closed**（见 {@link GatewayAuthVerifier}）。
     */
    public static final String GATEWAY_AUTH = "X-Gateway-Auth";

    /** 会员 id（仅当 {@link #GATEWAY_AUTH} 校验通过时才可信任） */
    public static final String MEMBER_ID = "X-Member-Id";

    /** 令牌版本号（网关已比对过，下游用于纵深防御地复核一次） */
    public static final String MEMBER_VER = "X-Member-Ver";

    /**
     * 管理员 id（= 管理端 JWT 的 {@code sub}；仅当 {@link #GATEWAY_AUTH} 校验通过时才可信任）。
     *
     * <p>P8-2a 起本进程的后台端点是这三个头的**唯一消费者**：
     * {@code /api/admin/**} 的身份判定不再有第二条路（不验签、不查库，见
     * {@code com.mall.demo.admin.support.AdminIdentityResolver}）。
     */
    public static final String ADMIN_ID = "X-Admin-Id";

    /** 管理端令牌版本号（= JWT 的 {@code ver}，网关已比对过；本进程只记录、不再复核） */
    public static final String ADMIN_VER = "X-Admin-Ver";

    private GatewayAuthHeaders() {
    }
}
