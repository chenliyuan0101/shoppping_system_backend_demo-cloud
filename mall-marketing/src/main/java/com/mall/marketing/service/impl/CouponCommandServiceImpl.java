package com.mall.marketing.service.impl;

import com.mall.marketing.domain.Coupon;
import com.mall.marketing.domain.CouponMember;
import com.mall.marketing.mapper.CouponMapper;
import com.mall.marketing.mapper.CouponMemberMapper;
import com.mall.marketing.service.CouponCommandService;
import com.mall.marketing.support.BusinessException;
import com.mall.marketing.support.CouponRules;
import com.mall.common.support.MallTime;
import com.mall.marketing.support.constant.CouponMemberStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import com.mall.common.support.MemberId;

/**
 * 券三态命令实现：<b>三态的全部并发语义押在三条条件 UPDATE 的影响行数上</b>
 * （{@code CouponMemberMapper}），本类负责的是"影响 0 行到底意味着什么"。
 *
 * <h2>lock 的判定顺序（每一步都对应一条对外文案，顺序即契约）</h2>
 * <ol>
 *   <li>券不存在 / 不属于该会员 → 400「优惠券不可用」
 *       （与 {@code discountFor} 同文案：不区分"券不存在"和"不是你的券"，避免探测别人的券 id）；</li>
 *   <li>已被**本单**锁定 → 直接 {@code true}（<b>幂等</b>：调用方重试不会得到 409）；</li>
 *   <li>状态不是 UNUSED（别人锁的 / 已核销 / 已过期状态）→ 409「优惠券已被使用或失效」<b>文案不变</b>；</li>
 *   <li>单券过期时间已到 → 400「优惠券已过期」；模板停用 → 400「优惠券已停用」；
 *       模板不在有效窗内 → 400「优惠券已过期」；</li>
 *   <li>CAS {@code 0 → 3}：影响 1 行 = 抢到；影响 0 行 = 与并发方撞了，
 *       再读一次判断是不是"同一订单并发重复 lock"（是则幂等 true），否则 409。</li>
 * </ol>
 *
 * <h2>⚠️ lock 里**没有门槛校验**（这是有意的，不是漏了）</h2>
 * 门槛是"这一单的金额够不够"，而 lock 的入参里**没有 goodsTotal**（契约见
 * {@code CouponLockRequest}）。门槛由同一次下单里先行的 {@code discount}（或结算页的
 * {@code usable}）按金额判过——两处都拦会变成两套口径，而"金额"只有 trade 知道。
 * 若将来要求 lock 自己校验门槛，必须先给契约加 goodsTotal，而不是在这里猜一个金额。
 *
 * <h2>⚠️ 两次读库为什么不算"check-then-act"竞态</h2>
 * 第 2/3 步的读只用于**决定给出哪条文案**，真正的判定是第 5 步的 CAS：
 * 两个并发请求无论读到什么，最终只有一条 UPDATE 影响 1 行。读错的最坏后果是
 * "文案不够精确"（409 vs 400），而不是"一张券被锁两次"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponCommandServiceImpl implements CouponCommandService {

    private final CouponMapper couponMapper;
    private final CouponMemberMapper couponMemberMapper;

    /**
     * 每日对账阈值（小时）：{@code LOCKED} 且锁定时间早于 {@code now - 该值} 视为"锁太久"。
     *
     * <p>默认 2 小时，**必须大于支付超时**（单体 {@code mall.order.pay-timeout-minutes} 默认 30 分钟）：
     * 否则会把"用户还在待支付窗口内正常锁着的券"误解锁，等他支付时 {@code use} 就会返回 false。
     */
    @Value("${mall.marketing.stuck-lock-hours:2}")
    private long stuckLockHours;

    @Override
    @Transactional
    public boolean lock(Long memberId, Long couponMemberId, String orderNo) {
        if (memberId == null || couponMemberId == null || orderNo == null || orderNo.isBlank()) {
            // 参数不完整 → 不做任何改动。HTTP 面已在 DTO 上挡成 400（RequestValidator），
            // 这里是"被 Service 直接调用"（内部调用/测试）时的护栏：绝不写出一张没有归属的锁定券。
            return false;
        }
        CouponMember cm = couponMemberMapper.selectById(couponMemberId);
        if (cm == null || !cm.getMemberId().equals(memberId)) {
            throw new BusinessException(400, "优惠券不可用");
        }
        // 幂等：本单已经锁住了这一张（重复提交/重试都会走到这里）
        if (CouponRules.lockedBy(cm, orderNo)) {
            return true;
        }
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

        // === 真正的判定：CAS 0 → 3，影响行数即并发凭证 ===
        //      lock_time 与 order_no 在**同一条 UPDATE** 里写：不存在"锁上了但没记时间"的中间态，
        //      否则步骤 E 的每日对账就永远找不到这张券（那种券只会烂在库里，用户既用不了也看不见）。
        int claimed = couponMemberMapper.lockUnused(couponMemberId, memberId, orderNo, now);
        if (claimed == 1) {
            return true;
        }
        // 影响 0 行：要么别人先锁/已核销（→409），要么**同一订单**的两个并发请求撞在一起
        // （第一个已经锁上，第二个的 CAS 也失败，但它其实应该得到 true）。
        // 用当前读（FOR SHARE）重读一次区分这两者：普通 selectById 会读到本事务开始时的快照，
        // 那个快照里"别人刚提交的锁定"看不见，于是把幂等命中误判成 409。
        CouponMember after = couponMemberMapper.selectCurrentById(couponMemberId);
        if (CouponRules.lockedBy(after, orderNo)) {
            return true;
        }
        throw new BusinessException(409, "优惠券已被使用或失效");
    }

    @Override
    @Transactional
    public boolean use(Long memberId, Long couponMemberId, String orderNo) {
        if (memberId == null || couponMemberId == null || orderNo == null || orderNo.isBlank()) {
            return false;   // 与单体 useCoupon 的 null 语义一致：不动库，返回 false
        }
        int changed = couponMemberMapper.markUsed(couponMemberId, memberId, orderNo, MallTime.now());
        if (changed == 1) {
            return true;
        }
        // 幂等：已经是本单核销过的 USED → true，且**不刷新 use_time**
        // （markUsed 影响 0 行时根本没有执行 UPDATE；这里只是把结论翻译给调用方）。
        // 当前读：与 lock 同理，"CAS 失败后的兜底判断"必须看得见别人刚提交的核销，
        // 否则并发/紧随其后的重复回调会被误判成 false（调用方只会记日志 → 对账噪音）。
        CouponMember m = couponMemberMapper.selectCurrentById(couponMemberId);
        return m != null
                && memberId.equals(m.getMemberId())
                && m.getCouponStatus() != null
                && m.getCouponStatus() == CouponMemberStatus.USED
                && orderNo.equals(m.getOrderNo());
    }

    @Override
    @Transactional
    public boolean unlock(Long memberId, Long couponMemberId, String orderNo) {
        if (memberId == null || couponMemberId == null || orderNo == null || orderNo.isBlank()) {
            return false;
        }
        int changed = couponMemberMapper.markUnused(couponMemberId, memberId, orderNo);
        if (changed == 1) {
            return true;
        }
        // 当前读（同 lock/use 的理由）：幂等重试必须看见"上一次已经解锁/已被别人核销"的真实状态
        CouponMember m = couponMemberMapper.selectCurrentById(couponMemberId);
        if (m == null || !memberId.equals(m.getMemberId())) {
            return false;
        }
        // 幂等且**不误伤**：只有"这张券当前就是 UNUSED(0)"才算成功。
        // 刻意**不**把 USED(1) 也算成功：那等于告诉调用方"券已回滚"，而它其实已被核销;
        // 也刻意**不**把 LOCKED-by-别人 算成功：那就是"解锁了别人锁的券"（下一步别人 use 会失败）。
        return m.getCouponStatus() != null && m.getCouponStatus() == CouponMemberStatus.UNUSED;
    }

    /**
     * 每日对账：把"锁太久"的券解锁（实现见接口注释里的理由）。
     *
     * <p>两个刻意的细节：
     * <ol>
     *   <li><b>读与写用同一个阈值</b>：{@code selectStuckLocked(threshold)} 选出来的行，
     *       再用带同一阈值的条件 UPDATE 去解。这样"对账跑到一半时用户刚锁上的券"不可能被误放
     *       （它的 {@code lock_time} 必然晚于阈值，条件不成立）；</li>
     *   <li><b>每行一条 WARN 日志</b>（含 memberId/券id/orderNo/lockTime）：对账解锁的都是
     *       "本该由关单路径解锁却没解"的异常行，必须留下可人工核对的痕迹——
     *       否则"券莫名回到未使用"会变成无法追查的黑盒。</li>
     * </ol>
     * 幂等：重复执行只会解到还剩的行；一条都没解到时返回 0，不产生日志噪音。
     */
    @Override
    @Transactional
    public int unlockStuckLocks(int limit) {
        int cap = limit <= 0 ? 200 : limit;
        var threshold = MallTime.now().minusHours(Math.max(1, stuckLockHours));
        var stuck = couponMemberMapper.selectStuckLocked(threshold, cap);
        if (stuck.isEmpty()) {
            return 0;
        }
        int unlocked = 0;
        for (CouponMember cm : stuck) {
            int changed = couponMemberMapper.unlockStuck(cm.getId(), threshold);
            if (changed > 0) {
                unlocked++;
                log.warn("对账解锁超时未释放的券: memberId={} couponMemberId={} orderNo={} lockTime={} 阈值={}h",
                        cm.getMemberId(), cm.getId(), cm.getOrderNo(), cm.getLockTime(), stuckLockHours);
            }
        }
        if (unlocked > 0) {
            log.warn("券锁定对账本轮解锁 {} 张（候选 {} 张，阈值 {} 小时）", unlocked, stuck.size(), stuckLockHours);
        }
        return unlocked;
    }
}
