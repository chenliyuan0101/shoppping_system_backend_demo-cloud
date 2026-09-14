package com.mall.usercenter.service.impl;

import com.mall.usercenter.domain.Member;
import com.mall.usercenter.mapper.MemberMapper;
import com.mall.usercenter.service.MemberStatusService;
import com.mall.usercenter.support.dto.MemberStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会员状态只读实现：只读自己的表、只产出契约快照。
 *
 * <p>刻意保持"薄"（与 P0 的域服务接口同一套要求）：不放业务规则、不改状态。
 * 将来它被网关/别的服务以 HTTP 调用时，搬走的是调用点，不是逻辑。
 *
 * <p>逻辑删除由实体上的 {@code @TableLogic} 处理，因此这里不需要手写 {@code deleted = 0}：
 * 已删除会员在 {@code selectById}/{@code selectCount} 里天然不可见——
 * 这正是"会员不存在 → 401"这条语义的由来。
 */
@Service
@RequiredArgsConstructor
public class MemberStatusServiceImpl implements MemberStatusService {

    private final MemberMapper memberMapper;

    @Override
    @Transactional(readOnly = true)
    public long count() {
        Long total = memberMapper.selectCount(null);
        return total == null ? 0L : total;
    }

    @Override
    @Transactional(readOnly = true)
    public MemberStatusVO status(long memberId) {
        Member member = memberMapper.selectById(memberId);
        return member == null ? null : new MemberStatusVO(member.getId(), member.getNickname(), member.getStatus());
    }
}
