package com.mall.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.marketing.client.UserCenterMemberClient;
import com.mall.marketing.domain.Coupon;
import com.mall.marketing.domain.CouponMember;
import com.mall.marketing.dto.AdminCouponSaveRequest;
import com.mall.marketing.dto.CouponRecordVO;
import com.mall.marketing.mapper.CouponMapper;
import com.mall.marketing.mapper.CouponMemberMapper;
import com.mall.marketing.service.AdminCouponService;
import com.mall.marketing.support.BusinessException;
import com.mall.marketing.support.CouponStatusProjection;
import com.mall.marketing.support.PageKit;
import com.mall.marketing.support.PageResult;
import com.mall.marketing.support.RequestValidator;
import com.mall.marketing.support.constant.CouponStatus;
import com.mall.marketing.support.constant.CouponType;
import com.mall.marketing.support.constant.CouponValidType;
import com.mall.marketing.support.dto.MemberBriefVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 后台券模板管理 —— <b>逐行搬自单体 {@code com.mall.demo.sms.service.impl.AdminCouponServiceImpl}</b>
 * （P5 步骤 C）：校验顺序、错误码、文案、分页收敛、"已发放只允许改有效期"、"已发放不可删除"
 * 一律未改。三处**刻意**的差异：
 * <ol>
 *   <li>{@code records} 的会员信息从"会员域契约（同进程）"换成"调 user-center 的批量内部接口"
 *       —— 契约没变，变的只是它的实现方式（这正是 P0 批次 9 埋下契约的目的）；</li>
 *   <li>{@code records} 的 {@code couponStatus} 走 {@link CouponStatusProjection#toExternal}
 *       （三态投影：库里的 3 锁定中 → 对外 1 已使用）；</li>
 *   <li>{@code now}/{@code LocalDateTime} 与其它时间口径一致（本类不涉及时间比较，故无实际差异）。</li>
 * </ol>
 *
 * <p>⚠️ {@code records} 里的会员调用**失败即整体失败**（{@code UserCenterMemberClient} 会抛
 * 业务异常/500）：这与单体改造前的行为一致（同进程契约抛异常 → 500）。
 * 刻意**不**做"取不到就返回 null 用户名"的降级：领取记录页显示的是"谁领了券"，
 * 静默空用户名会让人以为数据丢了；宁可报错（P4 在评价昵称上做的是 fail-open，
 * 因为那里"匿名用户"是可接受的展示，这里不是）。
 */
@Service
@RequiredArgsConstructor
public class AdminCouponServiceImpl implements AdminCouponService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CouponMapper couponMapper;
    private final CouponMemberMapper couponMemberMapper;
    /** 会员域的批量查询契约（**不得直连 ums_member**：那张表属会员域，营销域没有访问权） */
    private final UserCenterMemberClient userCenterMemberClient;
    /** 请求参数校验(约束注解写在 DTO 字段上，此处统一触发) */
    private final RequestValidator requestValidator;

    @Override
    @Transactional(readOnly = true)
    public PageResult<Coupon> page(String keyword, Integer status, long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, 50);
        LambdaQueryWrapper<Coupon> wrapper = new LambdaQueryWrapper<Coupon>()
                .like(StringUtils.hasText(keyword), Coupon::getName, keyword)
                .eq(status != null, Coupon::getStatus, status)
                .orderByDesc(Coupon::getCreateTime);
        long total = couponMapper.selectCount(wrapper);
        List<Coupon> list = couponMapper.selectList(wrapper.last("LIMIT " + ((page - 1) * size) + "," + size));
        return PageResult.of(total, page, size, list);
    }

    @Override
    @Transactional
    public Long create(AdminCouponSaveRequest request) {
        validateBase(request);
        Coupon coupon = new Coupon();
        fill(coupon, request);
        coupon.setStatus(CouponStatus.ENABLED);
        coupon.setReceivedCount(0);
        couponMapper.insert(coupon);
        return coupon.getId();
    }

    @Override
    @Transactional
    public void update(Long id, AdminCouponSaveRequest request) {
        Coupon coupon = require(id);
        boolean issued = coupon.getReceivedCount() != null && coupon.getReceivedCount() > 0;
        if (issued) {
            // 已发放：只允许调整有效期
            if (StringUtils.hasText(request.getValidStartTime())) {
                coupon.setValidStartTime(parseTime(request.getValidStartTime()));
            }
            if (StringUtils.hasText(request.getValidEndTime())) {
                coupon.setValidEndTime(parseTime(request.getValidEndTime()));
            }
            if (request.getValidDays() != null) {
                coupon.setValidDays(request.getValidDays());
            }
        } else {
            validateBase(request);
            fill(coupon, request);
        }
        couponMapper.updateById(coupon);
    }

    @Override
    public void disable(Long id) {
        Coupon coupon = require(id);
        Coupon update = new Coupon();
        update.setId(coupon.getId());
        update.setStatus(CouponStatus.DISABLED);
        couponMapper.updateById(update);
    }

    @Override
    public void enable(Long id) {
        require(id);
        Coupon update = new Coupon();
        update.setId(id);
        update.setStatus(CouponStatus.ENABLED);
        couponMapper.updateById(update);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Coupon coupon = require(id);
        if (coupon.getReceivedCount() != null && coupon.getReceivedCount() > 0) {
            throw new BusinessException(409, "该券已有人领取，无法删除(可停用)");
        }
        couponMapper.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<CouponRecordVO> records(Long templateId, long pageNum, long pageSize) {
        require(templateId);
        long page = PageKit.page(pageNum);
        long size = PageKit.size(pageSize, 50);
        LambdaQueryWrapper<CouponMember> wrapper = new LambdaQueryWrapper<CouponMember>()
                .eq(CouponMember::getTemplateId, templateId)
                .orderByDesc(CouponMember::getReceiveTime);
        long total = couponMemberMapper.selectCount(wrapper);
        List<CouponMember> list = couponMemberMapper.selectList(
                wrapper.last("LIMIT " + ((page - 1) * size) + "," + size));
        // 会员信息走会员域契约（跨进程）：POST /internal/v1/user/member/batch
        Map<Long, MemberBriefVO> memberMap = userCenterMemberClient
                .members(list.stream().map(CouponMember::getMemberId).toList())
                .stream().collect(Collectors.toMap(MemberBriefVO::getId, m -> m, (a, b) -> a));
        List<CouponRecordVO> vos = list.stream().map(cm -> {
            CouponRecordVO vo = new CouponRecordVO();
            vo.setId(cm.getId());
            vo.setMemberId(cm.getMemberId());
            MemberBriefVO m = memberMap.get(cm.getMemberId());
            vo.setMemberUsername(m == null ? null : m.getUsername());
            vo.setMemberNickname(m == null ? null : m.getNickname());
            // 三态投影：库里 3(LOCKED) → 对外 1(已使用)，与 /api/coupon/mine 同一口径
            vo.setCouponStatus(CouponStatusProjection.toExternal(cm.getCouponStatus()));
            vo.setReceiveTime(cm.getReceiveTime());
            vo.setExpireTime(cm.getExpireTime());
            vo.setOrderNo(cm.getOrderNo());
            vo.setUseTime(cm.getUseTime());
            return vo;
        }).toList();
        return PageResult.of(total, page, size, vos);
    }

    // ---------- private ----------

    private Coupon require(Long id) {
        Coupon coupon = couponMapper.selectById(id);
        if (coupon == null) {
            throw new BusinessException(404, "券模板不存在");
        }
        return coupon;
    }

    private void validateBase(AdminCouponSaveRequest request) {
        requestValidator.check(request);
        // 门槛 0 = 无门槛；此时减免额若大于门槛（例如"满 0 减 100 元"）会在下单时把实付算成负数，
        // 这里直接拦掉配置层面的坑（下单侧另外还有封顶兜底）
        if (request.getThresholdAmount() > 0 && request.getDiscountAmount() > request.getThresholdAmount()) {
            throw new BusinessException(400, "减免金额不能大于门槛金额");
        }
        Integer type = request.getType() == null ? CouponType.FULL_REDUCTION : request.getType();
        if (type != CouponType.FULL_REDUCTION) {
            throw new BusinessException(400, "暂仅支持满减券");
        }
        Integer validType = request.getValidType() == null ? CouponValidType.FIXED_RANGE : request.getValidType();
        if (validType == CouponValidType.FIXED_RANGE && !StringUtils.hasText(request.getValidStartTime())) {
            throw new BusinessException(400, "固定时间段类型需填写开始时间");
        }
    }

    private void fill(Coupon coupon, AdminCouponSaveRequest request) {
        coupon.setName(request.getName().trim());
        coupon.setType(request.getType() == null ? CouponType.FULL_REDUCTION : request.getType());
        coupon.setThresholdAmount(request.getThresholdAmount() == null ? 0L : request.getThresholdAmount());
        coupon.setDiscountAmount(request.getDiscountAmount());
        coupon.setTotalCount(request.getTotalCount());
        coupon.setPerMemberLimit(request.getPerMemberLimit() == null ? 1 : request.getPerMemberLimit());
        coupon.setValidType(request.getValidType() == null ? CouponValidType.FIXED_RANGE : request.getValidType());
        coupon.setValidStartTime(StringUtils.hasText(request.getValidStartTime())
                ? parseTime(request.getValidStartTime()) : null);
        coupon.setValidEndTime(StringUtils.hasText(request.getValidEndTime())
                ? parseTime(request.getValidEndTime()) : null);
        coupon.setValidDays(request.getValidDays());
    }

    private LocalDateTime parseTime(String s) {
        try {
            return LocalDateTime.parse(s, TIME);
        } catch (Exception e) {
            throw new BusinessException(400, "时间格式错误，示例 yyyy-MM-ddTHH:mm:ss");
        }
    }
}
