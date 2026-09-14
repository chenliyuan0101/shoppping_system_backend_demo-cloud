package com.mall.usercenter.support.dto;

/**
 * 成员状态快照（P3 §4.4 ② 的 Redis 载荷）。
 *
 * <p>网关与各业务服务只读这份缓存，**不查 {@code ums_member}**：
 * 网关据此判断"会员还在不在 / 有没有被禁用"，业务服务据此拿昵称等展示字段。
 * 写入方只有一个——本服务（login/logout/改密/禁用/资料变更时刷新）。
 *
 * <p>刻意做**窄**：不含密码、手机号、头像等敏感/无关字段。缓存是会到处传播的数据，
 * 放什么进去等于把它发到所有服务的内存里。
 *
 * @param memberId 会员 id
 * @param nickname 昵称（展示用）
 * @param status   状态：0 禁用 / 1 正常（{@code EnableStatus}）
 */
public record MemberStatusVO(Long memberId, String nickname, Integer status) {
}
