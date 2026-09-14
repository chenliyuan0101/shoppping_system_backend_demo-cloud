package com.mall.admin.support.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会员档案快照：user-center {@code com.mall.usercenter.support.dto.MemberSnapshotVO} 的契约快照
 * （{@code POST /internal/v1/user/member/page} 的元素形状、{@code GET /internal/v1/user/member/{id}/snapshot} 的形状）。
 *
 * <p>它是"会员管理"用的完整档案（含头像、状态、注册时间），与 {@code MemberBriefVO}（别的域展示姓名/电话）
 * 分开——BFF 只拿自己该看的那部分。不含密码。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemberSnapshotVO {

    private Long id;

    private String username;

    private String nickname;

    private String phone;

    private String avatar;

    /** 0禁用 1正常 */
    private Integer status;

    private LocalDateTime createTime;
}
