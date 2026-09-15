package com.mall.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.marketing.domain.Coupon;
import com.mall.marketing.domain.CouponMember;
import com.mall.marketing.mapper.CouponMapper;
import com.mall.marketing.mapper.CouponMemberMapper;
import com.mall.marketing.service.CouponQueryService;
import com.mall.marketing.support.BusinessException;
import com.mall.marketing.support.CouponRules;
import com.mall.common.support.MallTime;
import com.mall.marketing.support.constant.CouponMemberStatus;
import com.mall.marketing.support.dto.CouponBriefVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import com.mall.common.support.MemberId;

/**
 * 券查询契约实现：券的可用性规则与抵扣计算<b>只在本域内实现</b>。
 *
 * <p>本类整体搬自单体 {@code com.mall.demo.sms.service.impl.CouponQueryServiceImpl}
 * （它自己又是从 {@code oms.OrderServiceImpl} 的 {@code resolveDiscount}/{@code usableCoupons}
 * 原样搬过来的）：<b>校验顺序、错误码、错误文案、抵扣封顶、过滤条件一律未改</b>——
 * 5 条文案就是 C1 基线，trade 拆过去之后仍要逐字透传给用户。
 *
 * <p><b>唯一的实现差异（写在这里，不藏着）</b>：{@code now} 取自 {@link MallTime#now()}
 * （Asia/Shanghai），而不是单体的 {@code LocalDateTime.now()}（JVM 默认时区）。
 * 本机两者相同；差异只在"JVM 时区不是 +08:00"时出现（容器里很常见），
 * 而那时用 JVM 默认时区会让券的过期判断整体偏 8 小时——即"券提前过期或该过期却还能用"。
 * 这是刻意的、与全站业务时区口径一致的偏差，不影响任何一条对外文案。
 */
@Service
@RequiredArgsConstructor
public class CouponQueryServiceImpl implements CouponQueryService {

    private final CouponMapper couponMapper;
    private final CouponMemberMapper couponMemberMapper;

    @Override
    @Transactional(readOnly = true)
    public long discountFor(Long memberId, Long couponMemberId, long goodsTotal) {
        // 单体原文：券 id 为 null = 本单不用券 → 0。**不是参数错误**（详见 CouponDiscountRequest 注释）
        if (couponMemberId == null) {
            return 0L;
        }
        CouponMember cm = couponMemberMapper.selectById(couponMemberId);
        if (cm == null || !cm.getMemberId().equals(memberId)) {
            throw new BusinessException(400, "优惠券不可用");
        }
        // 三态下这里的含义变宽了一点：[1 已使用]、[2 已过期状态]、[3 锁定中] 都落这条 409。
        // 文案必须不变（C1）：用户在下单页看到的老提示就是这一条。
        if (cm.getCouponStatus() != null && cm.getCouponStatus() != CouponMemberStatus.UNUSED) {
            throw new BusinessException(409, "优惠券已被使用或失效");
        }
        LocalDateTime now = MallTime.now();
        if (!CouponRules.notExpired(cm, now)) {
            throw new BusinessException(400, "优惠券已过期");
        }
        Coupon coupon = couponMapper.selectById(cm.getTemplateId());
        if (!CouponRules.enabled(coupon)) {
            throw new BusinessException(400, "优惠券已停用");
        }
        if (!CouponRules.inValidWindow(coupon, now)) {
            throw new BusinessException(400, "优惠券已过期");
        }
        if (!CouponRules.meetsThreshold(coupon, goodsTotal)) {
            throw new BusinessException(400, "未满足优惠券使用门槛");
        }
        // 抵扣额必须封顶到商品金额：否则"无门槛 + 大额券"会把 pay_amount 算成负数，
        // 污染订单/支付/退款三张表的金额（也会让当日统计的支付金额变小）。
        // ⚠️ 这条封顶**留在营销域**（方案 §4.3）：它是券的业务规则，不是交易域的。
        long discount = coupon.getDiscountAmount() == null ? 0L : coupon.getDiscountAmount();
        return Math.max(0L, Math.min(discount, goodsTotal));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CouponBriefVO> usableCoupons(Long memberId, long goodsTotal) {
        // 口径未改：只筛 UNUSED(0)。三态之后这行**天然**排除了 LOCKED(3)——
        // 锁定中的券不是"可用券"（它已经被某一单占用），这正是三态要的语义。
        List<CouponMember> members = couponMemberMapper.selectList(new LambdaQueryWrapper<CouponMember>()
                .eq(CouponMember::getMemberId, memberId)
                .eq(CouponMember::getCouponStatus, CouponMemberStatus.UNUSED));
        LocalDateTime now = MallTime.now();
        List<Long> templateIds = members.stream().map(CouponMember::getTemplateId).distinct().toList();
        if (templateIds.isEmpty()) {
            return List.of();
        }
        var couponMap = couponMapper.selectBatchIds(templateIds).stream()
                .filter(CouponRules::enabled)
                .collect(java.util.stream.Collectors.toMap(Coupon::getId, c -> c));
        return members.stream()
                .filter(m -> CouponRules.notExpired(m, now))
                .map(m -> {
                    Coupon c = couponMap.get(m.getTemplateId());
                    if (c == null) {
                        return null;
                    }
                    if (c.getThresholdAmount() != null && goodsTotal < c.getThresholdAmount()) {
                        return null;
                    }
                    CouponBriefVO vo = new CouponBriefVO();
                    vo.setId(m.getId());
                    vo.setName(c.getName());
                    vo.setThresholdAmount(c.getThresholdAmount());
                    vo.setDiscountAmount(c.getDiscountAmount());
                    vo.setExpireTime(m.getExpireTime());
                    return vo;
                })
                .filter(Objects::nonNull)
                .toList();
    }
}
