package com.mall.usercenter.service;

import com.mall.usercenter.support.dto.MemberStatusVO;

/**
 * 会员状态的只读入口（P3-1 落地的最小契约）。
 *
 * <p>只做两件事：数数、取某个会员的状态快照。它是 §4.4 ② 的落点——
 * 网关和业务服务要的"会员还在不在、禁没禁用"，都由这里产出并写进 Redis 缓存。
 *
 * <p>完整的会员契约（批量快照 / 分页 / 搜索 / 管理端写操作）在 P3-3/P3-4
 * 随 auth & ums 的代码整体搬过来时补齐；这里先只做"跑得起来、连得上库"的最小闭环，
 * 避免一次性搬一大坨代码却无法验证。
 */
public interface MemberStatusService {

    /** 会员总数（不含已逻辑删除） */
    long count();

    /**
     * 会员状态快照；不存在（或已逻辑删除）返回 {@code null}。
     *
     * <p>返回 null 而不是抛异常：调用方（缓存刷新器）需要区分"会员不存在"与"查询失败"，
     * 前者是正常业务结果（写一个"不存在"的负缓存），后者才该重试。
     */
    MemberStatusVO status(long memberId);
}
