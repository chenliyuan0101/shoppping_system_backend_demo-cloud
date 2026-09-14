package com.mall.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台会员信息(不含密码)。
 *
 * <p><b>C1 契约：逐字对齐单体 {@code com.mall.demo.admin.dto.MemberAdminVO}</b>——
 * 10 个字段名、类型、以及<b>全部字段都会被序列化</b>（含 null）这一点都必须一致，
 * 因为前端 {@code views/member/MemberList.vue} 直接读这些 key：
 * 列表/详情页的"订单数 / 累计实付 / 评价数"三项读的就是 {@code orderCount}/{@code totalPaid}/{@code commentCount}。
 *
 * <p>本批（P7 后半）的值来源变化：
 * <ul>
 *   <li>前 7 个字段来自 user-center 的 {@code /member/page} 与 {@code /member/{id}/snapshot}；</li>
 *   <li>{@code orderCount}/{@code totalPaid} 来自单体交易域的
 *       {@code /internal/v1/stat/member-order-brief[/batch]}（**值**比单体多了"列表页也补数"这一步，
 *       形状/键不变——详见 {@link com.mall.admin.service.impl.AdminMemberServiceImpl} 的注释）；</li>
 *   <li>{@code commentCount} <b>恒为 null</b>（与单体现状逐字相同）：评价表已随评价域搬去
 *       {@code mall-review}，单体里这个数也没有来源，字段保留只为不改响应形状。</li>
 * </ul>
 */
@Data
@Schema(description = "后台会员信息")
public class MemberAdminVO {

    private Long id;
    private String username;
    private String nickname;
    private String phone;
    private String avatar;
    private Integer status;
    private LocalDateTime createTime;
    /** 订单数 */
    private Long orderCount;
    /** 累计实付(分) */
    private Long totalPaid;
    /**
     * 评价数。
     *
     * <p><b>P4 批次 3 起为 {@code null}</b>：评价表（{@code pms_comment}）已随评价域搬去
     * {@code mall-review}，商品域不再持有它，因此这个数在单体里已经没有来源。
     * 字段**保留**是为了不改变后台响应形状（前端 {@code MemberList.vue} 直接读这个 key）；
     * 回填要等 BFF 调 review 的内部端点（盘点报告 R9），届时只改赋值处、不动契约。
     * P7 把会员域搬到 BFF 之后，这条判断不变：**本批不回填它**。
     */
    private Long commentCount;
}
