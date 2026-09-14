package com.mall.usercenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.usercenter.domain.Member;
import com.mall.usercenter.mapper.MemberMapper;
import com.mall.usercenter.service.MemberQueryService;
import com.mall.usercenter.support.PageKit;
import com.mall.usercenter.support.PageResult;
import com.mall.usercenter.support.dto.MemberBriefVO;
import com.mall.usercenter.support.dto.MemberSnapshotVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 会员查询契约实现：只做"读会员表 + 转契约快照"，不在这里堆业务规则。
 */
@Service
@RequiredArgsConstructor
public class MemberQueryServiceImpl implements MemberQueryService {

    private final MemberMapper memberMapper;

    @Override
    @Transactional(readOnly = true)
    public MemberBriefVO brief(Long memberId) {
        if (memberId == null) {
            return null;
        }
        Member member = memberMapper.selectById(memberId);
        return member == null ? null : toBrief(member);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberBriefVO> briefs(Collection<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return List.of();
        }
        return memberMapper.selectBatchIds(memberIds).stream()
                .map(MemberQueryServiceImpl::toBrief)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<>());
        return count == null ? 0 : count;
    }

    @Override
    @Transactional(readOnly = true)
    public MemberSnapshotVO snapshot(Long memberId) {
        if (memberId == null) {
            return null;
        }
        Member member = memberMapper.selectById(memberId);
        return member == null ? null : toSnapshot(member);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<MemberSnapshotVO> page(String keyword, Integer status,
                                            LocalDateTime createTimeStart, LocalDateTime createTimeEnd,
                                            long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, 50);
        // 过滤条件整体是本域的查询知识，调用方只描述"要什么"，不描述"怎么查"
        LambdaQueryWrapper<Member> wrapper = new LambdaQueryWrapper<Member>()
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Member::getUsername, keyword)
                        .or().like(Member::getPhone, keyword)
                        .or().like(Member::getNickname, keyword))
                .eq(status != null, Member::getStatus, status)
                .ge(createTimeStart != null, Member::getCreateTime, createTimeStart)
                .lt(createTimeEnd != null, Member::getCreateTime, createTimeEnd)
                .orderByDesc(Member::getCreateTime);
        long total = memberMapper.selectCount(wrapper);
        List<Member> list = memberMapper.selectList(wrapper.last("LIMIT " + ((page - 1) * size) + "," + size));
        return PageResult.of(total, page, size, list.stream().map(MemberQueryServiceImpl::toSnapshot).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> searchIds(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        return memberMapper.selectList(new LambdaQueryWrapper<Member>()
                        .like(Member::getUsername, keyword)
                        .or().like(Member::getPhone, keyword)
                        .or().like(Member::getNickname, keyword))
                .stream().map(Member::getId).toList();
    }

    private static MemberBriefVO toBrief(Member member) {
        return new MemberBriefVO(member.getId(), member.getUsername(), member.getNickname(), member.getPhone());
    }

    private static MemberSnapshotVO toSnapshot(Member member) {
        return new MemberSnapshotVO(member.getId(), member.getUsername(), member.getNickname(),
                member.getPhone(), member.getAvatar(), member.getStatus(), member.getCreateTime());
    }
}
