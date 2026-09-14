package com.mall.marketing.dto;

import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * 后台券模板分页查询请求（{@code POST /internal/v1/marketing/admin/coupon/page} 的请求体）。
 *
 * <p>字段名与单体后台 controller 的入参**逐字对应**：{@code keyword}（券名关键字）、
 * {@code status}（0启用 1停用）、{@code pageNum}/{@code pageSize}（来自 {@code PageQuery}）。
 *
 * <p>⚠️ 页码/条数的收敛**不在这里**做：它是营销域自己的分页规则（{@code PageKit} 夹到
 * {@code [1,10000]} / {@code [1,50]}），放在属主域内才不会出现"两个调用方各自夹一次、
 * 夹出不同的上限"。这里的注解只挡住"负页号"这种明显错误。
 */
@Data
public class AdminCouponPageRequest {

    private String keyword;

    /** 0启用 1停用；null=全部 */
    private Integer status;

    @PositiveOrZero(message = "页码不能为负")
    private Long pageNum;

    @PositiveOrZero(message = "每页条数不能为负")
    private Long pageSize;
}
