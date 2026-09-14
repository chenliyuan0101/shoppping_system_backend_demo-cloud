package com.mall.demo.common.dto;

/**
 * 成员状态快照（P3 §4.4 ② 的 Redis 载荷 / 内部接口响应体）。
 *
 * <p>与 {@code mall-user-center} 的同名类**逐字段一致**（{@code {memberId, nickname, status}}）：
 * 它既写进共有的 Redis 缓存，也作为 {@code GET /internal/v1/user/member/{id}/status} 的响应体，
 * 因此两侧字段名不同就会静默地让"会员状态"永远读不到（表现为全员 401 或全员放行）。
 *
 * <p>刻意做窄：不含密码、手机号、头像等敏感字段——缓存是会传播到所有服务内存里的数据。
 */
public record MemberStatusVO(Long memberId, String nickname, Integer status) {
}
