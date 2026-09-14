package com.mall.usercenter.support.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会员档案快照（域间契约）：后台会员列表/详情展示所需字段。
 *
 * <p>与 {@link MemberBriefVO} 的区别：brief 是"别的域展示会员姓名/电话"用的最小集合；
 * 本快照是"会员管理"用的完整档案（含头像、状态、注册时间）。
 * 分成两个契约而不是塞进一个"大而全"的 DTO，是为了让消费方只拿到自己该看的那部分——
 * 例如评价/订单列表只需要昵称，就不该顺带拿到会员状态与注册时间。
 *
 * <p>注意：<b>不含密码</b>（与 {@code MemberAdminVO} 的对外约定一致），也不含 {@code deleted} 等持久层字段。
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

    /** 0禁用 1正常，见 {@code support.constant.EnableStatus} */
    private Integer status;

    private LocalDateTime createTime;
}
