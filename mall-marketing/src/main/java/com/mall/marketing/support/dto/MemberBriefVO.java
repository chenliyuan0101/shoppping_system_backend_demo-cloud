package com.mall.marketing.support.dto;

import lombok.Data;

/**
 * 会员简要信息 —— 营销域从 user-center 取会员信息时的**跨进程契约副本**
 * （字段与 user-center 的 {@code support/dto/MemberBriefVO}、单体的
 * {@code common.dto.MemberBriefVO} 逐字相同）。
 *
 * <p><b>为什么后台"领取记录"需要它</b>：发券记录要显示"是谁领的"（用户名/昵称）。
 * 改造前这件事在单体里是**直连 {@code ums_member} 表**做的（P0 的 B 类违规）；
 * P0 批次 9 改成走会员域契约，P3 会员域搬走后契约变远程，P5 步骤 C 连券一起搬过来——
 * 因此这里的取数方式只剩一条：调 user-center 的内部接口
 * （{@code POST /internal/v1/user/member/batch}）。**不得直连 {@code ums_member}**：
 * 那张表已经不在任何营销域的库/权限里，写一句 SQL 都跑不起来（而这正是我们要的护栏）。
 *
 * <p>⚠️ 只保留展示需要的四个字段：营销域**不需要**知道会员的手机号之外的任何东西，
 * 契约做窄能让"顺手用会员域的其它字段"在类型层面就不可能（方案 §2.5 原则三）。
 * （{@code phone} 保留是因为 user-center 的契约里有它，删字段会让两侧副本不再逐字相同。）
 */
@Data
public class MemberBriefVO {

    private Long id;
    private String username;
    private String nickname;
    private String phone;
}
