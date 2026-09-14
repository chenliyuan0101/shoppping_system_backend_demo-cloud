package com.mall.marketing.coupon;

import com.mall.marketing.service.CouponQueryService;
import com.mall.marketing.support.BusinessException;
import com.mall.marketing.support.MarketingTestBase;
import com.mall.marketing.support.constant.CouponMemberStatus;
import com.mall.marketing.support.dto.CouponBriefVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>券的可用性规则与抵扣计算的逐字回归</b>（{@code usable} / {@code discount}）。
 *
 * <p>本类守的是 C1 兼容性要点：{@code CouponQueryServiceImpl} 是从单体
 * {@code oms.OrderServiceImpl.resolveDiscount} → {@code sms.CouponQueryServiceImpl}
 * 一路原样搬过来的，**校验顺序、错误码、错误文案、抵扣封顶**都必须一字未改
 * （方案 §4.3）。所以这里的断言全部是"逐字比对文案"，而不是"能跑就行"。
 *
 * <p>另外覆盖了三态带来的**新语义**：锁定中的券（status=3）在 {@code discount} 里
 * 走与"已使用"同一条 409（旧的 {@code != UNUSED} 判定天然如此），
 * 而可用券列表仍然只筛 0——即"锁定的券不再是可用券"。
 */
class CouponQueryMySqlTest extends MarketingTestBase {

    @Autowired
    private CouponQueryService couponQueryService;

    // ==================================================================
    // discount：抵扣计算（含封顶）
    // ==================================================================

    @Test
    @DisplayName("[discount] 没选券（couponMemberId=null）→ 0，不报错（这是正常路径：不用券下单）")
    void discountIsZeroWhenNoCouponChosen() {
        assertEquals(0L, couponQueryService.discountFor(memberId, null, 10_000L),
                "不用券是**正常**下单路径，不能变成 400（否则所有不选券的下单全挂）");
    }

    @Test
    @DisplayName("[discount] 满足门槛 → 返回券面额")
    void discountHappyPath() {
        long couponId = newCouponMember(newTemplateWithThreshold(2000L, 500L));
        assertEquals(500L, couponQueryService.discountFor(memberId, couponId, 3000L));
    }

    @Test
    @DisplayName("[discount] 封顶：券面额 > 商品金额时只抵商品金额（不许把 pay_amount 算成负数）")
    void discountIsCappedToGoodsTotal() {
        long couponId = newCouponMember(newTemplate(5000L));

        assertEquals(3000L, couponQueryService.discountFor(memberId, couponId, 3000L),
                "抵扣必须封顶到商品金额：否则'无门槛大额券'会让订单/支付/退款三张表的金额变负");
        assertEquals(0L, couponQueryService.discountFor(memberId, couponId, 0L),
                "商品金额为 0 时抵扣必须是 0（Math.max(0, ...) 的作用）");
    }

    @Test
    @DisplayName("[discount 文案①] 券不是本人的（或不存在）→ 400「优惠券不可用」（逐字）")
    void discountRejectsOthersCoupon() {
        long othersCoupon = newCouponMember(newTemplate(500L), memberId + 1_234L,
                CouponMemberStatus.UNUSED, LocalDateTime.now().plusDays(7), null, null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponQueryService.discountFor(memberId, othersCoupon, 3000L));
        assertEquals(400, e.getCode());
        assertEquals("优惠券不可用", e.getMessage());

        BusinessException missing = assertThrows(BusinessException.class,
                () -> couponQueryService.discountFor(memberId, 9_199_999_999L, 3000L));
        assertEquals(400, missing.getCode());
        assertEquals("优惠券不可用", missing.getMessage(),
                "'不存在'与'不是你的'必须同文案：否则可以拿它探测别人有哪些券 id");
    }

    @Test
    @DisplayName("[discount 文案②] 券已使用 → 409「优惠券已被使用或失效」（逐字）")
    void discountRejectsUsedCoupon() {
        long couponId = newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.USED,
                LocalDateTime.now().plusDays(7), "T-OLD", LocalDateTime.now().minusHours(2));

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponQueryService.discountFor(memberId, couponId, 3000L));
        assertEquals(409, e.getCode());
        assertEquals("优惠券已被使用或失效", e.getMessage());
    }

    @Test
    @DisplayName("[discount 三态] 锁定中的券（status=3）→ 同一条 409（旧口径：非 UNUSED 即 409）")
    void discountRejectsLockedCoupon() {
        long couponId = newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.LOCKED,
                LocalDateTime.now().plusDays(7), "T-LOCK", null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponQueryService.discountFor(memberId, couponId, 3000L));
        assertEquals(409, e.getCode(), "锁定中的券不能再被第二单算钱");
        assertEquals("优惠券已被使用或失效", e.getMessage(), "文案不变：用户在下单页看到的老提示就是这条");
    }

    @Test
    @DisplayName("[discount 文案③] 单券已过期 → 400「优惠券已过期」（逐字）")
    void discountRejectsExpiredCoupon() {
        long couponId = newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.UNUSED,
                LocalDateTime.now().minusMinutes(1), null, null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponQueryService.discountFor(memberId, couponId, 3000L));
        assertEquals(400, e.getCode());
        assertEquals("优惠券已过期", e.getMessage());
    }

    @Test
    @DisplayName("[discount 文案④] 模板已停用 → 400「优惠券已停用」（逐字）")
    void discountRejectsDisabledTemplate() {
        long couponId = newCouponMember(newDisabledTemplate(500L));

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponQueryService.discountFor(memberId, couponId, 3000L));
        assertEquals(400, e.getCode());
        assertEquals("优惠券已停用", e.getMessage());
    }

    @Test
    @DisplayName("[discount 文案⑤] 模板不在有效窗内 → 400「优惠券已过期」（与单券过期同文案）")
    void discountRejectsOutOfTemplateWindow() {
        long couponId = newCouponMember(newWindowExpiredTemplate(500L));

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponQueryService.discountFor(memberId, couponId, 3000L));
        assertEquals(400, e.getCode());
        assertEquals("优惠券已过期", e.getMessage(),
                "模板窗口过期走的是第 5 条分支，文案与单券过期相同（C1 逐字保留）");
    }

    @Test
    @DisplayName("[discount 文案⑥] 未达门槛 → 400「未满足优惠券使用门槛」（逐字）")
    void discountRejectsBelowThreshold() {
        long couponId = newCouponMember(newTemplateWithThreshold(5000L, 500L));

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponQueryService.discountFor(memberId, couponId, 4999L));
        assertEquals(400, e.getCode());
        assertEquals("未满足优惠券使用门槛", e.getMessage());

        // 门槛是 >=：5000 元（分）时刚好可用（边界必须包含，否则"满 50 减 5"会被用户投诉）
        assertEquals(500L, couponQueryService.discountFor(memberId, couponId, 5000L));
    }

    // ==================================================================
    // usable：可用券列表
    // ==================================================================

    @Test
    @DisplayName("[usable] 只列出未使用的券：锁定中 / 已使用 / 已过期 一个都不出现")
    void usableListsOnlyUnusedCoupons() {
        long unusedTemplate = newTemplate(500L);
        long unusedCoupon = newCouponMember(unusedTemplate);
        // 另外三张：锁定中、已使用、已过期（状态或时间上都不该出现在"可用"里）
        newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.LOCKED,
                LocalDateTime.now().plusDays(7), "T-L1", null);
        newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.USED,
                LocalDateTime.now().plusDays(7), "T-U1", LocalDateTime.now().minusHours(1));
        newCouponMember(newTemplate(500L), memberId, CouponMemberStatus.UNUSED,
                LocalDateTime.now().minusMinutes(1), null, null);

        List<CouponBriefVO> usable = couponQueryService.usableCoupons(memberId, 10_000L);

        assertEquals(1, usable.size(), "只应该有那一张真正可用的券，实际：" + usable);
        CouponBriefVO vo = usable.get(0);
        assertEquals(unusedCoupon, vo.getId(), "返回的 id 是**用户券 id**（不是模板 id）——trade 拿它去 lock");
        assertEquals(500L, vo.getDiscountAmount().longValue());
        assertEquals(0L, vo.getThresholdAmount().longValue());
        assertNotNull(vo.getExpireTime());
        assertNotNull(vo.getName());
    }

    @Test
    @DisplayName("[usable] 门槛/模板停用过滤：金额不够或模板停用的券不算可用")
    void usableFiltersByThresholdAndTemplateStatus() {
        long tooExpensive = newTemplateWithThreshold(5000L, 500L);
        long disabled = newDisabledTemplate(500L);
        long ok = newTemplate(500L);
        newCouponMember(tooExpensive);
        newCouponMember(disabled);
        long okCoupon = newCouponMember(ok);

        List<CouponBriefVO> usable = couponQueryService.usableCoupons(memberId, 3000L);

        assertEquals(List.of(okCoupon), usable.stream().map(CouponBriefVO::getId).toList(),
                "门槛 5000 的券在 3000 的订单里不可用；模板停用的券更不可用");
        // 金额够了就出现（门槛按 >= 判）
        assertEquals(2, couponQueryService.usableCoupons(memberId, 5000L).size(),
                "5000 的订单里：门槛券与无门槛券都可用（停用的那张仍然不可用）");
    }

    @Test
    @DisplayName("[usable] 别人的券不会出现在我的可用券列表里")
    void usableIsMemberScoped() {
        long othersCoupon = newCouponMember(newTemplate(500L), memberId + 4_321L,
                CouponMemberStatus.UNUSED, LocalDateTime.now().plusDays(7), null, null);
        long mine = newCouponMember(newTemplate(500L));

        List<Long> ids = couponQueryService.usableCoupons(memberId, 10_000L).stream()
                .map(CouponBriefVO::getId).toList();

        assertTrue(ids.contains(mine));
        assertTrue(!ids.contains(othersCoupon), "列表必须按 memberId 过滤（跨会员泄漏券信息是安全问题）");
    }

    // ==================================================================
    // 数据面：本服务连的是自己的库，且数据真的搬过来了
    // ==================================================================

    @Test
    @DisplayName("[数据面] 本服务的数据源是 mall_marketing（不是单体的 mall）")
    void usesOwnSchema() {
        assertEquals("mall_marketing", schemaName(),
                "marketing 必须连自己的库；连到 mall 说明拆库没生效（那会让 P5 变成一句空话）");
        assertTrue(countOf("SELECT COUNT(*) FROM sms_coupon") > 0);
        assertTrue(countOf("SELECT COUNT(*) FROM sms_coupon_member") > 0);
    }

    // ==================================================================
    // 迁移期对账用例已**移除**（P5 步骤 F）
    //
    // 原来这里有一条 `migratedRowsMatchSourceExactly`：用跨库 SQL 比对
    // **源库（mall）那两张券表**与营销域自己的 `sms_coupon*`，守"迁移没丢行"。
    // （注释里刻意不写那个带库名的表引用：`.dsh-notes/p5-stepF-drop.ps1` 会 grep 它，
    //   而删表前置断言必须保持"零命中、不设例外"——连注释也不放过，否则例外会越加越多。）
    // 步骤 F 会**删掉 `mall` 里那两张表**，届时它必然红；而"靠捕获异常跳过"只会让它静默消失。
    //
    // 判据没有丢，只是**搬到了它真正该在的地方**——删表前置断言：
    //   `.dsh-notes/p5-stepF-drop.ps1` 第 3 步会在**执行 DROP 之前**当场重新证明
    //   "四个方向的 id 差集 + 逐行字段差 = 0|0|0|0|0"，任一非 0 就拒绝删除。
    // 这样做的额外好处是**边界更干净**：营销域的测试不该去查别人的 schema
    // （拆库的意义就是让这种跨 schema 依赖消失），迁移期的对账属于"迁移动作"，不属于稳态回归。
    // ==================================================================
}
