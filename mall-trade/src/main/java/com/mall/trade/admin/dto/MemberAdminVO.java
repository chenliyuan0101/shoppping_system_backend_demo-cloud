package com.mall.trade.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台会员信息(不含密码)。
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
     */
    private Long commentCount;
}
