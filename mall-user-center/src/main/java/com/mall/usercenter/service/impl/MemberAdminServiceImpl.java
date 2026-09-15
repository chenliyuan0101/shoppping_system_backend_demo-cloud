package com.mall.usercenter.service.impl;

import com.mall.usercenter.domain.Member;
import com.mall.usercenter.mapper.MemberMapper;
import com.mall.usercenter.service.MemberAdminService;
import com.mall.usercenter.support.BusinessException;
import com.mall.usercenter.support.MemberStatusCache;
import com.mall.usercenter.support.TokenVersionService;
import com.mall.usercenter.support.constant.EnableStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.mall.common.support.MemberId;

/**
 * 会员管理写契约实现：状态校验 + 落库 + 禁用时失效令牌，全部收在会员域内。
 *
 * <p>对外错误码与文案与改造前一致（404"会员不存在"、400"状态值仅支持 0禁用 1正常"）。
 */
@Service
@RequiredArgsConstructor
public class MemberAdminServiceImpl implements MemberAdminService {

    private final MemberMapper memberMapper;
    private final TokenVersionService tokenVersionService;
    /** 状态变更后清掉成员状态缓存（§4.4 ②）：否则别的请求可能在 TTL 内读到旧状态 */
    private final MemberStatusCache memberStatusCache;

    @Override
    @Transactional
    public void updateStatus(Long memberId, Integer status) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BusinessException(404, "会员不存在");
        }
        if (status == null || (status != EnableStatus.DISABLED && status != EnableStatus.ENABLED)) {
            throw new BusinessException(400, "状态值仅支持 0禁用 1正常");
        }
        Member update = new Member();
        update.setId(memberId);
        update.setStatus(status);
        memberMapper.updateById(update);
        // 缓存失效只删不写：下次读取时按需重建，避免"写了一份旧状态进去"
        memberStatusCache.evict(memberId);
        if (status == EnableStatus.DISABLED) {
            // 禁用即失效：令牌版本号 +1，该会员所有旧 token 立刻不可用。
            // 注意这两件事的分工：版本号负责"立刻踢下线"（网关比对后直接 401），
            // 缓存清理负责"状态读取别读到旧的"——缺任何一个都会出现难查的不一致。
            tokenVersionService.bump(TokenVersionService.TYPE_USER, memberId);
        }
    }
}
