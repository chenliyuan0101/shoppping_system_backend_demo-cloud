package com.mall.trade.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 申请售后请求。
 */
@Data
@Schema(description = "申请售后请求")
public class RefundApplyRequest {

    @Schema(description = "订单号", example = "202609070000000001")
    private String orderNo;

    @Schema(description = "类型 1仅退款(未发货) 2退货退款(已收货/待收货)", example = "1")
    private Integer refundType;

    @Schema(description = "申请原因", example = "不想要了")
    @NotBlank(message = "请选择申请原因")
    private String reason;

    @Schema(description = "问题描述(可空)", example = "商品与描述不符")
    private String description;

    @Schema(description = "凭证图(可空)")
    private List<String> images;
}
