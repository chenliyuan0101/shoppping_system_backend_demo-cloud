package com.mall.trade.common.contract;

import com.mall.trade.common.dto.CouponBriefVO;

import java.util.List;
import com.mall.common.support.MemberId;

/**
 * 券查询契约（跨服务）：<b>券的可用性规则与抵扣计算由营销域实现</b>，交易域只表达"我要用这张券"。
 *
 * <p><b>P5 步骤 C</b>：本接口从 {@code com.mall.trade.sms.service.CouponQueryService}
 * **上移到 {@code common.contract}**（跨服务契约，P0 纪律：跨域只能走契约），
 * 实现从"同进程查本地表"换成"调 marketing 的内部接口 / 锁表"。
 * 调用点（{@code OrderServiceImpl}）一行不用改——这正是 P0 批次 8b 把这些方法做成
 * "只收发 DTO 的纯接口"的目的。
 *
 * <p>⚠️ 本地实现（{@code sms.service.impl.CouponQueryServiceImpl}）**已随步骤 C 删除**，
 * 且**刻意不留运行时开关**：切完旧表就没用了，留开关只会留一个"切错了才发现"的坑
 * （P3-4 的教训：开关与路由必须同时生效，否则写一边读另一边）。
 *
 * <p>实现见 {@code app.MarketingRemoteConfig}（组装根）→ {@code common.client.MarketingClient}。
 */
public interface CouponQueryService {

    /**
     * 算这张券能抵多少钱（分，已封顶到商品金额）。
     *
     * <p>{@code couponMemberId == null} 表示"本单不用券" → 返回 0（正常路径，不是错误）。
     * 校验失败由营销域抛业务异常（400/409 + 中文文案），{@code MarketingClient} 原样透传。
     */
    long discountFor(Long memberId, Long couponMemberId, long goodsTotal);

    /** 该会员在该金额下可用的券（结算页展示） */
    List<CouponBriefVO> usableCoupons(Long memberId, long goodsTotal);
}
