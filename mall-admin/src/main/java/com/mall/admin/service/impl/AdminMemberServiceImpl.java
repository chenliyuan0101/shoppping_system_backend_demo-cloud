package com.mall.admin.service.impl;

import com.mall.admin.client.TradeStatClient;
import com.mall.admin.client.UserCenterMemberClient;
import com.mall.admin.dto.MemberAdminVO;
import com.mall.admin.service.AdminMemberService;
import com.mall.admin.support.BusinessException;
import com.mall.admin.support.DashboardCache;
import com.mall.common.support.TxCallbacks;
import com.mall.admin.support.dto.MemberOrderBriefVO;
import com.mall.admin.support.dto.MemberSnapshotVO;
import com.mall.admin.support.dto.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 后台会员管理：分页/详情（订单·消费统计）/启停（P7 §4）。
 *
 * <h2>与单体 {@code AdminMemberServiceImpl} 的关系（C1）</h2>
 * 对外表现逐字相同（路径 / 参数 / 响应字段 / code / 文案 / HTTP 恒 200）；
 * 分工也照抄单体那张表：
 * <pre>
 *   分页主查 → 会员域（过滤条件本来就作用在 ums_member 上）
 *   订单数/累计实付 → 交易域
 *   启停 → 会员域的**写契约**（状态校验 + "禁用即失效令牌"都留在属主域）
 *   commentCount → 恒 null（评价表已归 mall-review，单体里也没有来源）
 * </pre>
 *
 * <h2>§4 无 N+1：一页恰好两次远程调用</h2>
 * <pre>
 *   ① POST /internal/v1/user/member/page      —— 分页主查（拿 total/list/当页 id）
 *   ② POST /internal/v1/stat/member-order-brief/batch —— **一次**批量补数（当页所有 id 一次问完）
 * </pre>
 * 次数与 {@code pageSize}、页码都无关（判据：第 1 页与第 5 页的调用次数相等）。
 * 单体在这一步是"列表不补数、只有详情补"（列表的 {@code orderCount}/{@code totalPaid} 恒为 null），
 * 所以本批是**值层面的增强、形状层面零变化**：{@code MemberAdminVO} 的键集合与单体一字不差
 * （C1 的指纹比的是键路径/code/文案/id 集合，不比业务数值），前端列表页也不渲染这两个字段
 * （只有详情弹窗渲染，见 {@code MemberList.vue}）。
 *
 * <h2>补数失败怎么办：降级为 null，而不是把整页打回 500</h2>
 * 交易域挂了，会员列表本身（主查）仍然是好的 ⇒ 返回 {@code code=0} 且那两项为 null
 * ——这正是单体今天列表的表现，所以"降级态"与"切换前的常态"逐字一致。
 * 留一条 {@code log.warn} 指向对账。**不缓存**（列表本来就不进缓存）。
 *
 * <h2>启停：跨服务写 + 提交后失效</h2>
 * ① 校验 {@code status} 是否为空（文案与单体 {@code RemoteMemberAdmin} 逐字一致）；
 * ② 调会员域写契约（那边落库 + 清状态缓存 + 禁用时 bump 会员令牌版本，**BFF 不重复做**）；
 * ③ 失效看板缓存——用 {@link TxCallbacks#afterCommitOrNow}：
 *    本方法当前**没有本地事务**（不写本库），所以它立即执行；
 *    但这个写法保证"将来一旦被本地事务包住，失效不会跑在提交之前"（回滚 ⇒ 不发失效）。
 *    该性质由用例显式钉住（外层事务回滚 ⇒ 缓存代不变）。
 *
 * <p>⚠️ 本类**不加** {@code @Transactional(readOnly = true)}（单体有）：单体那个注解是为了本地查表，
 * 而这里的"读"全是跨进程调用——挂一个只读事务只会白占一个数据库连接直到下游返回，
 * 正是 §4.6 要避免的"BFF 把连接池耗在下游延迟上"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMemberServiceImpl implements AdminMemberService {

    /** 与单体 {@code AdminMemberServiceImpl.detail} 逐字一致（C1 文案） */
    private static final String MEMBER_NOT_FOUND = "会员不存在";

    /** 与单体 {@code UserCenterRemoteConfig.RemoteMemberAdmin#updateStatus} 逐字一致（C1 文案） */
    private static final String INVALID_STATUS = "状态值仅支持 0禁用 1正常";

    private final UserCenterMemberClient userCenterMemberClient;
    private final TradeStatClient tradeStatClient;
    private final DashboardCache dashboardCache;

    @Override
    public PageResult<MemberAdminVO> page(String keyword, Integer status,
                                         LocalDateTime createTimeStart, LocalDateTime createTimeEnd,
                                         long pageNum, long pageSize) {
        // ① 分页主查（属主域）
        PageResult<MemberSnapshotVO> result = userCenterMemberClient.page(
                keyword, status, createTimeStart, createTimeEnd, pageNum, pageSize);
        List<MemberSnapshotVO> rows = result.getList() == null ? List.of() : result.getList();

        // ② 一次批量补数（当页所有 id）
        Map<Long, MemberOrderBriefVO> briefs = orderBriefs(rows);

        List<MemberAdminVO> list = rows.stream()
                .map(member -> toVO(member, briefs.get(member.getId())))
                .toList();
        return PageResult.of(result.getTotal(), result.getPageNum(), result.getPageSize(), list);
    }

    @Override
    public MemberAdminVO detail(Long id) {
        MemberSnapshotVO member = userCenterMemberClient.snapshot(id);
        if (member == null) {
            // 与单体同：data 为 null 的"查不到"在这里升级成 404「会员不存在」
            throw new BusinessException(404, MEMBER_NOT_FOUND);
        }
        MemberAdminVO vo = toVO(member, null);
        try {
            MemberOrderBriefVO brief = tradeStatClient.memberOrderBrief(id);
            if (brief != null) {
                vo.setOrderCount(brief.getOrderCount());
                vo.setTotalPaid(brief.getPaidAmount());
            }
        } catch (RuntimeException e) {
            // 详情主体（会员档案）是好的 ⇒ 仍是 code=0，只是订单口径两项为 null
            log.warn("会员详情订单口径降级: memberId={} 原因={} ⇒ orderCount/totalPaid 返回 null（需对账）",
                    id, e.getMessage());
        }
        // commentCount 不回填：评价数属于 mall-review（见 MemberAdminVO 的字段注释）
        return vo;
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        if (id == null || status == null) {
            // 单体 RemoteMemberAdmin 的原话：memberId/status 为空即 400（不把 null 转成 0）
            throw new BusinessException(400, INVALID_STATUS);
        }
        // 跨服务写：状态落库、状态缓存清理、禁用时 bump 会员令牌版本，全在会员域内完成
        userCenterMemberClient.updateStatus(id, status);
        // 提交后失效看板缓存（§5 第 8 条）：会员数是看板的组成部分之一，
        // 且这是本模块**唯一**的写路径——把"改数据 ⇒ 清缓存"的落点钉在这里。
        TxCallbacks.afterCommitOrNow(dashboardCache::evictAll);
    }

    /**
     * 批量补数：**一次**远程调用取回当页所有会员的订单口径。
     *
     * <p>空页不发请求（空 id 集合的批量调用没有意义；与单体 {@code ProductClient}/{@code UserCenterClient}
     * 对空集合的处理口径一致）——所以"总页数之外的空页"是 1 次调用而不是 2 次，
     * 这**不是** N+1：判据是"调用次数与页码无关"，而它只会更少。
     */
    private Map<Long, MemberOrderBriefVO> orderBriefs(List<MemberSnapshotVO> rows) {
        List<Long> ids = rows.stream()
                .map(MemberSnapshotVO::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, MemberOrderBriefVO> briefs = tradeStatClient.memberOrderBriefs(ids);
            return briefs == null ? Map.of() : new LinkedHashMap<>(briefs);
        } catch (RuntimeException e) {
            log.warn("会员列表订单补数降级: 本页 id 数={} 原因={} ⇒ orderCount/totalPaid 返回 null（需对账）",
                    ids.size(), e.getMessage());
            return Map.of();
        }
    }

    private static MemberAdminVO toVO(MemberSnapshotVO member, MemberOrderBriefVO brief) {
        MemberAdminVO vo = new MemberAdminVO();
        vo.setId(member.getId());
        vo.setUsername(member.getUsername());
        vo.setNickname(member.getNickname());
        vo.setPhone(member.getPhone());
        vo.setAvatar(member.getAvatar());
        vo.setStatus(member.getStatus());
        vo.setCreateTime(member.getCreateTime());
        if (brief != null) {
            vo.setOrderCount(brief.getOrderCount());
            vo.setTotalPaid(brief.getPaidAmount());
        }
        // brief 为 null（降级/无数据）⇒ 两个字段保持 null，与切换前单体列表的表现逐字一致
        return vo;
    }
}
