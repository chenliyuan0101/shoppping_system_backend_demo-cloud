package com.mall.marketing.coupon;

import com.mall.marketing.service.CouponCommandService;
import com.mall.marketing.support.BusinessException;
import com.mall.marketing.support.MarketingTestBase;
import com.mall.marketing.support.constant.CouponMemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>P5 批次 1 的核心验收：券三态在真库上的全部语义</b>
 * （{@code UNUSED ⇄ LOCKED → USED}，见《微服务改造方案.md》§4.3 / §4.3.1）。
 *
 * <p>本类**直接调 Service**（不经 HTTP），因为要证明的是状态机与并发本身；
 * HTTP 面（含"无令牌 403 / 带令牌取到真数据 / 文案逐字"）由
 * {@code InternalMarketingCouponApiMySqlTest} 覆盖，两条路径都必须有：
 * 只测 Service 会漏掉参数校验与鉴权，只测 HTTP 会漏掉并发语义。
 *
 * <p>⚠️ 本类所有断言都看**库里的真值**（{@code coupon_status} 是 0/1/2/3），
 * 不经过任何对外投影——投影是另一条链路的契约（{@code CouponStatusProjectionTest}）。
 */
class CouponTriStateMySqlTest extends MarketingTestBase {

    @Autowired
    private CouponCommandService couponCommandService;

    /** 本用例用的订单号（刻意带上 memberId，方便在库里一眼认出是谁的数据） */
    private String orderNo(String suffix) {
        return "T" + memberId + "-" + suffix;
    }

    // ==================================================================
    // lock：0 → 3
    // ==================================================================

    @Test
    @DisplayName("[lock] 首次锁定：影响 1 行 → true，库里 status=3 且写下了 order_no")
    void lockWinsAndWritesOrderNo() {
        long templateId = newTemplate(1000L);
        long couponId = newCouponMember(templateId);
        String no = orderNo("A1");
        assertEquals(0, dbStatusOf(couponId), "前置条件：券应该是未使用");

        assertTrue(couponCommandService.lock(memberId, couponId, no), "首次 lock 必须成功");

        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(couponId), "锁定后库里必须是 3（LOCKED）");
        assertEquals(no, dbOrderNoOf(couponId), "锁定必须同时写下占用它的订单号（否则这张券谁都对不上账）");
        assertNull(dateTimeOf("SELECT use_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponId),
                "锁定**不等于**核销：use_time 此刻必须还是 NULL");
    }

    @Test
    @DisplayName("[lock 幂等] 同一 orderNo 重复 lock → true，且不会改写库里的任何东西")
    void lockTwiceWithSameOrderIsIdempotent() {
        long couponId = newCouponMember(newTemplate(1000L));
        String no = orderNo("A2");

        assertTrue(couponCommandService.lock(memberId, couponId, no));
        assertTrue(couponCommandService.lock(memberId, couponId, no), "重复 lock（同单）必须幂等返回 true");

        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(couponId));
        assertEquals(no, dbOrderNoOf(couponId), "order_no 不能被第二次调用改写");
        assertNull(dateTimeOf("SELECT use_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponId));
    }

    @Test
    @DisplayName("[lock 冲突] 被别的订单锁住后再 lock → 409「优惠券已被使用或失效」（文案逐字）")
    void lockWhenLockedByAnotherOrderConflicts() {
        long couponId = newCouponMember(newTemplate(1000L));
        String first = orderNo("B1");
        String second = orderNo("B2");
        assertTrue(couponCommandService.lock(memberId, couponId, first));

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponCommandService.lock(memberId, couponId, second));

        assertEquals(409, e.getCode(), "抢不到必须是 409");
        assertEquals("优惠券已被使用或失效", e.getMessage(), "对外文案必须与旧实现逐字相同（C1）");
        assertEquals(first, dbOrderNoOf(couponId), "冲突方不得抢走别人的锁定（order_no 必须还是第一个单的）");
        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(couponId));
    }

    @Test
    @DisplayName("[lock 冲突] 已 USED 后再 lock → 同样 409（已核销的券不能回头再锁）")
    void lockWhenAlreadyUsedConflicts() {
        long couponId = newCouponMember(newTemplate(1000L), memberId, CouponMemberStatus.USED,
                LocalDateTime.now().plusDays(7), orderNo("C0"), LocalDateTime.now().minusHours(1));

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponCommandService.lock(memberId, couponId, orderNo("C1")));
        assertEquals(409, e.getCode());
        assertEquals("优惠券已被使用或失效", e.getMessage());
        assertEquals(CouponMemberStatus.USED, dbStatusOf(couponId), "失败的 lock 不得改动已核销的券");
    }

    @Test
    @DisplayName("[lock 冲突] 库里的 status=2（已过期状态）也走同一条 409（口径：非 UNUSED 即 409）")
    void lockWhenExpiredStatusConflicts() {
        long couponId = newCouponMember(newTemplate(1000L), memberId, CouponMemberStatus.EXPIRED,
                LocalDateTime.now().minusDays(1), null, null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponCommandService.lock(memberId, couponId, orderNo("D1")));
        assertEquals(409, e.getCode(), "旧的 discountFor 对 status!=0 一律 409，三态后必须保持一致");
        assertEquals("优惠券已被使用或失效", e.getMessage());
    }

    @Test
    @DisplayName("[lock 校验] 不是自己的券 → 400「优惠券不可用」（不区分'不存在'与'不是你的'）")
    void lockOthersCouponRejected() {
        long otherMember = memberId + 7_777L;
        long couponId = newCouponMember(newTemplate(1000L), otherMember, CouponMemberStatus.UNUSED,
                LocalDateTime.now().plusDays(7), null, null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponCommandService.lock(memberId, couponId, orderNo("E1")));
        assertEquals(400, e.getCode());
        assertEquals("优惠券不可用", e.getMessage());
        assertEquals(0, dbStatusOf(couponId), "别人的券一个字节都不能动");
    }

    @Test
    @DisplayName("[lock 校验] 券不存在 → 400「优惠券不可用」")
    void lockMissingCouponRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> couponCommandService.lock(memberId, 9_299_999_999L, orderNo("E2")));
        assertEquals(400, e.getCode());
        assertEquals("优惠券不可用", e.getMessage());
    }

    @Test
    @DisplayName("[lock 校验] 单券已过期（expire_time 在过去）→ 400「优惠券已过期」")
    void lockExpiredCouponRejected() {
        long couponId = newCouponMember(newTemplate(1000L), memberId, CouponMemberStatus.UNUSED,
                LocalDateTime.now().minusMinutes(1), null, null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponCommandService.lock(memberId, couponId, orderNo("F1")));
        assertEquals(400, e.getCode());
        assertEquals("优惠券已过期", e.getMessage());
        assertEquals(0, dbStatusOf(couponId));
    }

    @Test
    @DisplayName("[lock 校验] 模板已停用 → 400「优惠券已停用」")
    void lockDisabledTemplateRejected() {
        long couponId = newCouponMember(newDisabledTemplate(1000L));

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponCommandService.lock(memberId, couponId, orderNo("F2")));
        assertEquals(400, e.getCode());
        assertEquals("优惠券已停用", e.getMessage());
        assertEquals(0, dbStatusOf(couponId), "被停用的券不能被锁定（否则用户会先锁后不能用）");
    }

    @Test
    @DisplayName("[lock 校验] 模板不在有效窗内 → 400「优惠券已过期」")
    void lockOutOfTemplateWindowRejected() {
        long couponId = newCouponMember(newWindowExpiredTemplate(1000L));

        BusinessException e = assertThrows(BusinessException.class,
                () -> couponCommandService.lock(memberId, couponId, orderNo("F3")));
        assertEquals(400, e.getCode());
        assertEquals("优惠券已过期", e.getMessage(), "模板窗口过期与单券过期是同一条文案（旧实现亦然）");
    }

    @Test
    @DisplayName("[lock 参数] 订单号空/缺、券 id 缺失 → false（绝不写出没有归属的锁定券）")
    void lockWithIncompleteArgsChangesNothing() {
        long couponId = newCouponMember(newTemplate(1000L));

        assertFalse(couponCommandService.lock(memberId, couponId, null));
        assertFalse(couponCommandService.lock(memberId, couponId, "   "));
        assertFalse(couponCommandService.lock(memberId, null, orderNo("G1")));
        assertFalse(couponCommandService.lock(null, couponId, orderNo("G2")));

        assertEquals(0, dbStatusOf(couponId), "参数不完整时一次库都不该写");
        assertNull(dbOrderNoOf(couponId));
    }

    // ==================================================================
    // use：3 → 1（幂等）
    // ==================================================================

    @Test
    @DisplayName("[use] 锁定后核销：3 → 1 且写 use_time")
    void useAfterLockMarksUsed() {
        long couponId = newCouponMember(newTemplate(1000L));
        String no = orderNo("H1");
        assertTrue(couponCommandService.lock(memberId, couponId, no));

        assertTrue(couponCommandService.use(memberId, couponId, no), "本单锁的券必须能核销");

        assertEquals(CouponMemberStatus.USED, dbStatusOf(couponId));
        assertNotNull(dateTimeOf("SELECT use_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponId),
                "核销必须写 use_time（它是'券什么时候被用掉'的唯一证据）");
        assertEquals(no, dbOrderNoOf(couponId), "核销后 order_no 保留（它是'哪一单用掉的'的证据，unlock 才清）");
    }

    @Test
    @DisplayName("[use 幂等] 3→1 之后再 use → true，且 **use_time 不被刷新**")
    void useIsIdempotentAndKeepsUseTime() {
        long couponId = newCouponMember(newTemplate(1000L));
        String no = orderNo("H2");
        assertTrue(couponCommandService.lock(memberId, couponId, no));
        assertTrue(couponCommandService.use(memberId, couponId, no));
        LocalDateTime firstUseTime = dateTimeOf(
                "SELECT use_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponId);

        assertTrue(couponCommandService.use(memberId, couponId, no), "重复 use 必须幂等（支付回调可能重投）");

        LocalDateTime secondUseTime = dateTimeOf(
                "SELECT use_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponId);
        assertEquals(firstUseTime, secondUseTime,
                "重复 use 不得刷新 use_time：它是'首次核销时间'，被刷新会让对账/客服查不出真实核销时刻");
        assertEquals(CouponMemberStatus.USED, dbStatusOf(couponId));
    }

    @Test
    @DisplayName("[use] 不是本单锁的券 → false，且库里的状态/归属一字不动")
    void useByAnotherOrderReturnsFalse() {
        long couponId = newCouponMember(newTemplate(1000L));
        String lockedBy = orderNo("I1");
        assertTrue(couponCommandService.lock(memberId, couponId, lockedBy));

        assertFalse(couponCommandService.use(memberId, couponId, orderNo("I2")),
                "别的订单不能把这张券核销掉（否则两单共用一张券的抵扣）");

        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(couponId), "失败的 use 不得改动状态");
        assertEquals(lockedBy, dbOrderNoOf(couponId));
        assertNull(dateTimeOf("SELECT use_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponId));
    }

    @Test
    @DisplayName("[use] 未锁定的券（0）直接 use → false（核销必须建立在锁之上）")
    void useWithoutLockReturnsFalse() {
        long couponId = newCouponMember(newTemplate(1000L));

        assertFalse(couponCommandService.use(memberId, couponId, orderNo("I3")));

        assertEquals(CouponMemberStatus.UNUSED, dbStatusOf(couponId));
        assertNull(dateTimeOf("SELECT use_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponId));
    }

    @Test
    @DisplayName("[use] 别人已核销的券 use → false（会员维度也是一道闸）")
    void useOthersCouponReturnsFalse() {
        long otherMember = memberId + 8_888L;
        long couponId = newCouponMember(newTemplate(1000L), otherMember, CouponMemberStatus.USED,
                LocalDateTime.now().plusDays(7), orderNo("J0"), LocalDateTime.now().minusMinutes(5));

        assertFalse(couponCommandService.use(memberId, couponId, orderNo("J0")),
                "同单号但不同会员也不能核销（memberId 是纵深防御，不是重复校验）");
        assertEquals(CouponMemberStatus.USED, dbStatusOf(couponId));
    }

    @Test
    @DisplayName("[use 参数] 参数不完整 → false（支付回调拿 false 只记日志，不得让支付失败）")
    void useWithIncompleteArgsReturnsFalse() {
        long couponId = newCouponMember(newTemplate(1000L));
        String no = orderNo("J1");
        assertTrue(couponCommandService.lock(memberId, couponId, no));

        assertFalse(couponCommandService.use(memberId, couponId, null));
        assertFalse(couponCommandService.use(memberId, null, no));
        assertFalse(couponCommandService.use(null, couponId, no));

        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(couponId), "参数不完整不得误伤已锁定的券");
    }

    // ==================================================================
    // unlock：3 → 0（幂等、只动自己锁的那张）
    // ==================================================================

    @Test
    @DisplayName("[unlock] 解锁：3 → 0，清空 order_no（这就是修掉的存量缺陷：券能回来了）")
    void unlockReturnsCouponToUnused() {
        long couponId = newCouponMember(newTemplate(1000L));
        String no = orderNo("K1");
        assertTrue(couponCommandService.lock(memberId, couponId, no));

        assertTrue(couponCommandService.unlock(memberId, couponId, no));

        assertEquals(CouponMemberStatus.UNUSED, dbStatusOf(couponId), "取消/超时后券必须回到未使用");
        assertNull(dbOrderNoOf(couponId), "order_no 必须清空：留着旧单号会让下一次 lock 的幂等判据误判");
        assertNull(dateTimeOf("SELECT use_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponId));
    }

    @Test
    @DisplayName("[unlock 幂等] 已经是 0 再 unlock → true，且不误伤别的券")
    void unlockIsIdempotentWhenAlreadyUnused() {
        long first = newCouponMember(newTemplate(1000L));
        long second = newCouponMember(newTemplate(1000L));
        String no = orderNo("K2");
        assertTrue(couponCommandService.unlock(memberId, first, no),
                "本来就没锁过（0）也算'券处于期望终态' → true");

        // 另一张券被别的单锁着：解锁第一张绝不能碰到它
        String otherOrder = orderNo("K3");
        assertTrue(couponCommandService.lock(memberId, second, otherOrder));
        assertTrue(couponCommandService.unlock(memberId, first, no), "重复 unlock 仍然幂等 true");

        assertEquals(CouponMemberStatus.UNUSED, dbStatusOf(first));
        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(second), "解锁只动自己那一张，不能连坐");
        assertEquals(otherOrder, dbOrderNoOf(second));
    }

    @Test
    @DisplayName("[unlock] 不能解锁别人锁的券：3（别人的单） → false，状态与归属不变")
    void unlockCannotReleaseOthersLock() {
        long couponId = newCouponMember(newTemplate(1000L));
        String otherOrder = orderNo("L1");
        assertTrue(couponCommandService.lock(memberId, couponId, otherOrder));

        assertFalse(couponCommandService.unlock(memberId, couponId, orderNo("L2")),
                "不是本单锁的券不能解锁——否则会把别人的订单置于'下单成功但券被抢走'的状态");

        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(couponId));
        assertEquals(otherOrder, dbOrderNoOf(couponId));
    }

    @Test
    @DisplayName("[unlock] 已被本单核销（USED）的券 unlock → false（券已烧掉，不能'解锁'回未使用）")
    void unlockAfterUseReturnsFalse() {
        long couponId = newCouponMember(newTemplate(1000L));
        String no = orderNo("L3");
        assertTrue(couponCommandService.lock(memberId, couponId, no));
        assertTrue(couponCommandService.use(memberId, couponId, no));
        LocalDateTime useTime = dateTimeOf(
                "SELECT use_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponId);

        assertFalse(couponCommandService.unlock(memberId, couponId, no),
                "退款不动券是既有行为（RefundServiceImpl），所以解锁也不得把已核销的券放回来");

        assertEquals(CouponMemberStatus.USED, dbStatusOf(couponId));
        assertEquals(useTime, dateTimeOf(
                "SELECT use_time FROM mall_marketing.sms_coupon_member WHERE id = ?", couponId),
                "被拒绝的 unlock 不得抹掉核销痕迹");
        assertEquals(no, dbOrderNoOf(couponId), "已核销的券的 order_no 也不该被清（它是核销凭据）");
    }

    @Test
    @DisplayName("[unlock] 别人锁的券 + 别人的会员 id → false（memberId 与 orderNo 两道都要对）")
    void unlockOthersCouponReturnsFalse() {
        long otherMember = memberId + 9_999L;
        long couponId = newCouponMember(newTemplate(1000L), otherMember, CouponMemberStatus.LOCKED,
                LocalDateTime.now().plusDays(7), orderNo("M0"), null);

        assertFalse(couponCommandService.unlock(memberId, couponId, orderNo("M0")));
        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(couponId));
    }

    // ==================================================================
    // 全生命周期：领取 → 锁定 → 核销 ；以及 锁定 → 取消解锁 → 还能再用
    // ==================================================================

    @Test
    @DisplayName("[生命周期] 领取→锁定→核销 与 锁定→解锁→再锁定（券真的回来了）")
    void fullLifecycle() {
        long templateId = newTemplateWithThreshold(2000L, 500L);
        long couponId = newCouponMember(templateId);

        // ① 下单：锁定
        String firstOrder = orderNo("N1");
        assertTrue(couponCommandService.lock(memberId, couponId, firstOrder));
        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(couponId));

        // ② 下单失败/取消：解锁（旧实现缺的就是这一步）
        assertTrue(couponCommandService.unlock(memberId, couponId, firstOrder));
        assertEquals(CouponMemberStatus.UNUSED, dbStatusOf(couponId));

        // ③ 再下单：同一张券还能被新订单锁上（如果 unlock 没清 order_no，这一步会 409 或幂等误判）
        String secondOrder = orderNo("N2");
        assertTrue(couponCommandService.lock(memberId, couponId, secondOrder),
                "解锁后的券必须能被新订单锁定：这正是修掉'券被永久烧掉'的验收点");
        assertEquals(secondOrder, dbOrderNoOf(couponId));

        // ④ 支付成功：核销
        assertTrue(couponCommandService.use(memberId, couponId, secondOrder));
        assertEquals(CouponMemberStatus.USED, dbStatusOf(couponId));

        // ⑤ 再锁：已核销 → 409（文案逐字）
        BusinessException e = assertThrows(BusinessException.class,
                () -> couponCommandService.lock(memberId, couponId, orderNo("N3")));
        assertEquals(409, e.getCode());
        assertEquals("优惠券已被使用或失效", e.getMessage());
    }

    // ==================================================================
    // 并发（真库、真事务、真多线程）
    // ==================================================================

    @Test
    @DisplayName("[并发] 8 个线程同时 lock 同一张券（不同订单）→ **只有一方拿到**，其余 7 个 409 且文案逐字")
    void concurrentLockOnlyOneWinner() throws Exception {
        long couponId = newCouponMember(newTemplate(1000L));
        int threads = 8;

        List<String> failures = new CopyOnWriteArrayList<>();
        List<Integer> codes = new CopyOnWriteArrayList<>();
        List<Boolean> results = runConcurrently(threads, i -> {
            try {
                return couponCommandService.lock(memberId, couponId, orderNo("P" + i));
            } catch (BusinessException e) {
                codes.add(e.getCode());
                failures.add(e.getMessage());
                return false;
            }
        });

        long winners = results.stream().filter(Boolean::booleanValue).count();
        assertEquals(1, winners,
                "同一张券只允许一个订单锁定成功；拿到多个 = 条件 UPDATE 的语义被破坏了（会有两单共用一张券）");
        assertEquals(threads - 1, codes.size(), "其余线程必须以业务异常（409）失败，而不是静默 false");
        assertTrue(codes.stream().allMatch(c -> c == 409), "失败的线程必须都是 409，实际：" + codes);
        assertTrue(failures.stream().allMatch("优惠券已被使用或失效"::equals),
                "并发失败的文案也必须逐字是「优惠券已被使用或失效」，实际：" + failures);

        // 库里必须是"获胜那一单"的锁定，且只有 1 行被改动
        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(couponId));
        String winnerOrder = dbOrderNoOf(couponId);
        assertNotNull(winnerOrder, "锁定成功必须留下 order_no");
        assertTrue(winnerOrder.startsWith("T" + memberId + "-P"), "order_no 必须是某个参赛订单号，实际：" + winnerOrder);
        assertEquals(1L, countOf("""
                SELECT COUNT(*) FROM mall_marketing.sms_coupon_member
                 WHERE member_id = ? AND coupon_status = 3 AND order_no IS NOT NULL
                """, memberId), "只有这一张券处于锁定状态");
    }

    @Test
    @DisplayName("[并发] 8 个线程用**同一个 orderNo** 同时 lock → 全部 true（双击提交不产生 409）")
    void concurrentLockSameOrderIsIdempotent() throws Exception {
        long couponId = newCouponMember(newTemplate(1000L));
        String sameOrder = orderNo("Q1");
        int threads = 8;

        List<Throwable> errors = new CopyOnWriteArrayList<>();
        List<Boolean> results = runConcurrently(threads, i -> {
            try {
                return couponCommandService.lock(memberId, couponId, sameOrder);
            } catch (Throwable t) {
                errors.add(t);
                return false;
            }
        });

        assertTrue(errors.isEmpty(),
                "同一订单号的并发重复 lock 必须幂等（双击提交/网关重试），不能报 409，实际异常：" + errors);
        assertTrue(results.stream().allMatch(Boolean::booleanValue), "全部应为 true，实际：" + results);

        assertEquals(CouponMemberStatus.LOCKED, dbStatusOf(couponId));
        assertEquals(sameOrder, dbOrderNoOf(couponId));
        assertEquals(1L, countOf("""
                SELECT COUNT(*) FROM mall_marketing.sms_coupon_member WHERE member_id = ? AND coupon_status = 3
                """, memberId), "并发重复 lock 不得写出第二行锁定");
    }

    @Test
    @DisplayName("[并发] 一个线程 lock、另一个线程立刻 unlock/lock 竞争后，库里状态始终是自洽的（不出现 0 行多单一锁）")
    void concurrentLockAndUnlockStayConsistent() throws Exception {
        long couponId = newCouponMember(newTemplate(1000L));
        String orderA = orderNo("R1");
        String orderB = orderNo("R2");

        runConcurrently(2, i -> {
            try {
                if (i == 0) {
                    couponCommandService.lock(memberId, couponId, orderA);
                    couponCommandService.unlock(memberId, couponId, orderA);
                } else {
                    couponCommandService.lock(memberId, couponId, orderB);
                }
            } catch (BusinessException ignored) {
                // 竞争失败（409）是允许的结果之一：本用例只断言"最终状态自洽"
            }
            return true;
        });

        int status = dbStatusOf(couponId);
        String finalOrderNo = dbOrderNoOf(couponId);
        assertTrue(status == CouponMemberStatus.UNUSED || status == CouponMemberStatus.LOCKED,
                "终态只可能是 未使用 或 锁定中，实际：" + status);
        if (status == CouponMemberStatus.UNUSED) {
            assertNull(finalOrderNo, "回到未使用就必须没有 order_no，实际：" + finalOrderNo);
        } else {
            assertNotNull(finalOrderNo, "锁定中就必须有 order_no，实际：null");
        }
    }

    /** 让 {@code threads} 个线程尽量同时开跑，收集每个线程的返回值 */
    private List<Boolean> runConcurrently(int threads, java.util.function.IntFunction<Boolean> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.apply(idx);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "线程池没能同时就绪（用例环境问题，不是业务问题）");
            start.countDown();
            List<Boolean> results = new java.util.ArrayList<>();
            for (Future<Boolean> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
