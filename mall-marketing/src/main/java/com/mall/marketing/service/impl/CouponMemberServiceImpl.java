package com.mall.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.marketing.domain.Coupon;
import com.mall.marketing.domain.CouponMember;
import com.mall.marketing.dto.CouponTemplateVO;
import com.mall.marketing.dto.MyCouponVO;
import com.mall.marketing.mapper.CouponMapper;
import com.mall.marketing.mapper.CouponMemberMapper;
import com.mall.marketing.service.CouponMemberService;
import com.mall.marketing.support.BusinessException;
import com.mall.marketing.support.CouponRules;
import com.mall.marketing.support.CouponStatusProjection;
import com.mall.marketing.support.MallTime;
import com.mall.marketing.support.constant.CouponMemberStatus;
import com.mall.marketing.support.constant.CouponStatus;
import com.mall.marketing.support.constant.CouponValidType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会员侧券实现。一期每张券模板每会员限领 1 张(表 unique 保证，与 perMemberLimit>=1 语义一致)。
 *
 * <p><b>逐行搬自单体 {@code com.mall.demo.sms.service.impl.CouponServiceImpl}</b>
 * （P5 批次 2）：校验顺序、错误码、错误文案、并发防超发逻辑一律未改。
 * 三处**刻意**的差异，都写在对应代码旁：
 * <ol>
 *   <li>{@code now} 取 {@link MallTime#now()}（Asia/Shanghai）而不是 {@code LocalDateTime.now()}
 *       ——与批次 1 的 {@code CouponQueryServiceImpl} 同一口径，理由见那里；</li>
 *   <li>{@code mine} 的过滤与响应走 {@link CouponStatusProjection}（三态投影，批次 1 定的 C1 约束）；</li>
 *   <li>没有搬 {@code usableForTotal}（死代码：全仓无调用点）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class CouponMemberServiceImpl implements CouponMemberService {

    private final CouponMapper couponMapper;
    private final CouponMemberMapper couponMemberMapper;

    @Override
    @Transactional(readOnly = true)
    public List<CouponTemplateVO> available(Long memberId) {
        List<Coupon> templates = couponMapper.selectList(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getStatus, CouponStatus.ENABLED)
                .orderByAsc(Coupon::getId));
        LocalDateTime now = MallTime.now();
        List<Long> mineTemplateIds = memberId == null ? List.of()
                : couponMemberMapper.selectList(new LambdaQueryWrapper<CouponMember>()
                .eq(CouponMember::getMemberId, memberId)).stream()
                .map(CouponMember::getTemplateId).toList();

        return templates.stream()
                .filter(c -> CouponRules.inValidWindow(c, now))
                .map(c -> {
                    CouponTemplateVO vo = new CouponTemplateVO();
                    vo.setId(c.getId());
                    vo.setName(c.getName());
                    vo.setType(c.getType());
                    vo.setThresholdAmount(c.getThresholdAmount());
                    vo.setDiscountAmount(c.getDiscountAmount());
                    vo.setTotalCount(c.getTotalCount());
                    vo.setPerMemberLimit(c.getPerMemberLimit());
                    vo.setReceivedCount(c.getReceivedCount());
                    vo.setValidStartTime(c.getValidStartTime());
                    vo.setValidEndTime(c.getValidEndTime());
                    vo.setValidDays(c.getValidDays());
                    // "我是否领过这张模板"：**所有状态都算领过**（含已使用/已过期/锁定中）——
                    // 这与表上的 uk(member_id, template_id) 一致：同一模板一个会员只有一行，
                    // 已用掉的券不能让"领取"按钮重新变成可点（点下去会撞唯一键 → 409）。
                    vo.setReceived(mineTemplateIds.contains(c.getId()));
                    return vo;
                })
                .toList();
    }

    @Override
    @Transactional
    public void receive(Long memberId, Long templateId) {
        Coupon coupon = couponMapper.selectById(templateId);
        if (!CouponRules.enabled(coupon)) {
            throw new BusinessException(404, "券不存在或已停发");
        }
        if (!CouponRules.inValidWindow(coupon, MallTime.now())) {
            throw new BusinessException(409, "不在领取时间内");
        }
        Long already = couponMemberMapper.selectCount(new LambdaQueryWrapper<CouponMember>()
                .eq(CouponMember::getMemberId, memberId)
                .eq(CouponMember::getTemplateId, templateId));
        int limit = coupon.getPerMemberLimit() == null ? 1 : coupon.getPerMemberLimit();
        if (already != null && already >= Math.max(1, limit)) {
            throw new BusinessException(409, "已达每人限领数量");
        }
        // 已发量原子 +1(总量 NULL=不限；已领满则 0 行 → 防超发，封装见 CouponMapper.increaseReceived)
        if (couponMapper.increaseReceived(templateId) == 0) {
            throw new BusinessException(409, "券已被领完");
        }
        CouponMember member = new CouponMember();
        member.setTemplateId(templateId);
        member.setMemberId(memberId);
        member.setCouponStatus(CouponMemberStatus.UNUSED);
        member.setReceiveTime(MallTime.now());
        member.setExpireTime(expireOf(coupon));
        try {
            couponMemberMapper.insert(member);
        } catch (DuplicateKeyException e) {
            // 并发重复领取：把上面的 +1 抵消并提示(封装见 CouponMapper.decreaseReceived)。
            // ⚠️ 这一行是**逐行搬单体**的既有代码，刻意保留：本方法 @Transactional，
            // 紧随其后的 BusinessException 会让整个事务回滚（+1 与插入一起撤掉），
            // 所以当前事务边界下它是"冗余但无害"的；删掉它则会在将来有人调整事务边界
            // （如把 +1 挪进独立事务 / REQUIRES_NEW）时静默丢失这层补偿。
            couponMapper.decreaseReceived(templateId);
            throw new BusinessException(409, "已领取过该券");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyCouponVO> mine(Long memberId, Integer status) {
        // 过滤投影：0 → [0] / 1 → [1,3] / 2 → [2] / null → 不过滤 / 其它 → 不可能命中值
        List<Integer> dbStatuses = CouponStatusProjection.dbStatusFilter(status);
        List<CouponMember> members = couponMemberMapper.selectList(new LambdaQueryWrapper<CouponMember>()
                .eq(CouponMember::getMemberId, memberId)
                .in(dbStatuses != null, CouponMember::getCouponStatus, dbStatuses)
                .orderByDesc(CouponMember::getReceiveTime));
        List<Coupon> coupons = members.isEmpty() ? List.of()
                : couponMapper.selectBatchIds(members.stream().map(CouponMember::getTemplateId).distinct().toList());
        var couponMap = coupons.stream().collect(Collectors.toMap(Coupon::getId, c -> c));
        return members.stream().map(m -> {
            MyCouponVO vo = new MyCouponVO();
            vo.setId(m.getId());
            Coupon c = couponMap.get(m.getTemplateId());
            if (c != null) {
                vo.setName(c.getName());
                vo.setType(c.getType());
                vo.setThresholdAmount(c.getThresholdAmount());
                vo.setDiscountAmount(c.getDiscountAmount());
            }
            // 值投影：库里的 3(LOCKED) → 对外 1(已使用)；其余原样
            vo.setCouponStatus(CouponStatusProjection.toExternal(m.getCouponStatus()));
            vo.setReceiveTime(m.getReceiveTime());
            vo.setExpireTime(m.getExpireTime());
            vo.setOrderNo(m.getOrderNo());
            return vo;
        }).toList();
    }

    // ---------- private ----------

    private LocalDateTime expireOf(Coupon c) {
        if (c.getValidType() != null && c.getValidType() == CouponValidType.DAYS_AFTER_RECEIVE) {
            int days = c.getValidDays() == null ? 7 : c.getValidDays();
            return MallTime.now().plusDays(days);
        }
        return c.getValidEndTime();
    }
}
