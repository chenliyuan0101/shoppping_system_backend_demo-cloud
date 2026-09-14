package com.mall.demo.admin.service.impl;

import com.mall.demo.admin.dto.MemberAdminVO;
import com.mall.demo.admin.service.AdminMemberService;
import com.mall.demo.common.contract.MemberAdminService;
import com.mall.demo.common.contract.MemberQueryService;
import com.mall.demo.common.BusinessException;
import com.mall.demo.common.PageResult;
import com.mall.demo.common.dto.MemberOrderBriefVO;
import com.mall.demo.common.dto.MemberSnapshotVO;
import com.mall.demo.oms.service.OrderStatQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 后台会员管理：分页/详情(订单·消费统计)/启停。
 *
 * <p><b>P0 边界冻结</b>：本类不再直连 {@code ums_member}/{@code oms_order}/{@code pms_comment}
 * （原先 6 条跨域直连 + 3 个实体）。改造后的分工是：
 * <ul>
 *   <li><b>分页主查</b>交给会员域（过滤条件本来就作用在 {@code ums_member} 上）；</li>
 *   <li>详情里的订单数/累计实付问订单域；</li>
 *   <li>启停走会员域的<b>写契约</b>——"状态值校验"与"禁用即失效令牌"这条不变量都留在属主域，
 *       不再由后台侧顺手调 {@code TokenVersionService} 来完成。</li>
 * </ul>
 * 对外接口（路径/参数/响应字段/错误码文案）完全未变。
 *
 * <p><b>P4 批次 3 的一处数据缺口</b>：详情里的 {@code commentCount}（评价数）
 * 原来由商品域的 {@code ProductStatQueryService.commentCountByMember} 提供，
 * 而那个实现读的是 {@code pms_comment}——评价表已经搬去 {@code mall-review}，
 * 商品域不再持有这张表，因此本批**删掉了那个契约方法并停止回填本字段**
 * （{@code MemberAdminVO.commentCount} 保留在响应里，值为 {@code null}）。
 * 这正是盘点报告 R9 预判的形态：评价数属于评价域，应当由 BFF 调 review 的内部端点取，
 * 那属于后续批次（本批的目标是让"评价只有一个属主"成立，而不是继续从商品域读别人的表）。
 */
@Service
@RequiredArgsConstructor
public class AdminMemberServiceImpl implements AdminMemberService {

    private final MemberQueryService memberQueryService;
    private final MemberAdminService memberAdminService;
    private final OrderStatQueryService orderStatQueryService;

    @Override
    @Transactional(readOnly = true)
    public PageResult<MemberAdminVO> page(String keyword, Integer status,
                                          LocalDateTime createTimeStart, LocalDateTime createTimeEnd,
                                          long pageNum, long pageSize) {
        PageResult<MemberSnapshotVO> result = memberQueryService.page(
                keyword, status, createTimeStart, createTimeEnd, pageNum, pageSize);
        return PageResult.of(result.getTotal(), result.getPageNum(), result.getPageSize(),
                result.getList().stream().map(AdminMemberServiceImpl::toVO).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public MemberAdminVO detail(Long id) {
        MemberSnapshotVO member = memberQueryService.snapshot(id);
        if (member == null) {
            throw new BusinessException(404, "会员不存在");
        }
        MemberAdminVO vo = toVO(member);
        MemberOrderBriefVO orderBrief = orderStatQueryService.memberOrderBrief(id);
        vo.setOrderCount(orderBrief.getOrderCount());
        vo.setTotalPaid(orderBrief.getPaidAmount());
        // commentCount 不再回填：评价数属于 mall-review（见类注释的 P4 说明）
        return vo;
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        memberAdminService.updateStatus(id, status);
    }

    private static MemberAdminVO toVO(MemberSnapshotVO m) {
        MemberAdminVO vo = new MemberAdminVO();
        vo.setId(m.getId());
        vo.setUsername(m.getUsername());
        vo.setNickname(m.getNickname());
        vo.setPhone(m.getPhone());
        vo.setAvatar(m.getAvatar());
        vo.setStatus(m.getStatus());
        vo.setCreateTime(m.getCreateTime());
        return vo;
    }
}
