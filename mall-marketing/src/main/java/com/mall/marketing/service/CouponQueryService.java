package com.mall.marketing.service;

import com.mall.marketing.support.dto.CouponBriefVO;

import java.util.List;

/**
 * 券查询契约：<b>券的可用性规则与抵扣计算只在本域内实现</b>。
 *
 * <p>本接口是从单体 {@code oms.service.impl.OrderServiceImpl} 的两个私有方法
 * （{@code resolveDiscount} / {@code usableCoupons}）经 P0 批次 8b 搬到
 * {@code sms.CouponQueryService}、P5 再整体搬进本服务的——<b>校验顺序、错误码、错误文案、
 * 抵扣封顶、过滤条件一律未改</b>。唯一变化是它现在住在营销域：
 * "券怎么算"的知识不再泄漏到交易域（方案 §4.3）。
 *
 * <p>跨进程后这两个方法就是
 * {@code POST /internal/v1/marketing/coupon/{usable,discount}} 的宿主（批次 4 trade 客户端化）。
 */
public interface CouponQueryService {

    /**
     * 算这张券能抵多少钱（分）。
     *
     * <p>5 项校验逐条搬自单体（顺序即契约）：
     * <ol>
     *   <li>券存在且属于该会员 → 否则 400「优惠券不可用」；</li>
     *   <li>状态必须是 {@code UNUSED} → 否则 409「优惠券已被使用或失效」
     *       （注意：<b>没有 goodsTotal 的门槛校验</b>——门槛在上一步的 usable 里已按金额筛过）；</li>
     *   <li>未过期（{@code expire_time}）→ 否则 400「优惠券已过期」；</li>
     *   <li>模板启用 → 否则 400「优惠券已停用」；</li>
     *   <li>模板在有效窗内 → 否则 400「优惠券已过期」；</li>
     *   <li>满足门槛 → 否则 400「未满足优惠券使用门槛」。</li>
     * </ol>
     *
     * <p>⚠️ 返回值**封顶到商品金额**：{@code Math.max(0, Math.min(discount, goodsTotal))}。
     * 这是刻意的业务规则（不属于交易域）：否则"无门槛 + 大额券"会把 {@code pay_amount}
     * 算成负数，污染订单/支付/退款三张表的金额。
     *
     * @param memberId       会员 id（券的归属校验用）
     * @param couponMemberId 用户券 id；<b>null = 不使用券 → 返回 0</b>（正常路径，不报错）
     * @param goodsTotal     商品总额（分）
     * @return 抵扣额（分），已封顶到 {@code goodsTotal}
     */
    long discountFor(Long memberId, Long couponMemberId, long goodsTotal);

    /**
     * 该会员在该金额下可用的券列表（结算页展示）。
     *
     * <p>过滤口径与单体逐字相同：只取 {@code coupon_status = 0}（**锁定中的券不是可用券**，
     * 这正是三态带来的必然结论）、模板启用、单券未过期、满足门槛。
     * 因此本方法**不需要**改动就天然满足 P5 的"可用券只筛 0（不变）"。
     */
    List<CouponBriefVO> usableCoupons(Long memberId, long goodsTotal);
}
