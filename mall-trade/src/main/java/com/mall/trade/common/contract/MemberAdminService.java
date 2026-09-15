package com.mall.trade.common.contract;

import com.mall.common.support.MemberId;

/**
 * 会员域的<b>管理写契约</b>（供后台使用）。
 *
 * <p>为什么和 {@link MemberQueryService} 分开：读契约与写契约混在一个接口里，
 * 调用方只要注入一次就同时拿到"能改会员状态"的能力——权限面被动扩大。
 * 拆开之后，"谁需要写"是显式的。
 *
 * <p>为什么"禁用即失效"这条规则落在会员域：它不是后台的展示逻辑，而是会员域的<b>不变量</b>——
 * 会员一旦被禁用，其所有已签发的令牌必须立刻失效。把规则放在属主域（现在是 user-center，
 * 见 {@code /internal/v1/user/member/{id}/status} 的实现），才不会出现"某个调用方忘了 bump 令牌版本"的漏网
 * （改造前后台侧曾自己调 TokenVersionService 完成这件事）。
 *
 * <p>P3-4 起本契约的唯一实现是 {@link com.mall.trade.app.UserCenterRemoteConfig} 里的远程实现。
 */
public interface MemberAdminService {

    /**
     * 变更会员状态。
     *
     * @param memberId 会员 id
     * @param status   0 禁用 / 1 正常
     * @throws com.mall.trade.common.BusinessException 404 会员不存在；400 状态值非法
     */
    void updateStatus(Long memberId, Integer status);
}
