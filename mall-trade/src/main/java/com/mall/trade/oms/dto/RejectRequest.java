package com.mall.trade.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 后台拒绝售后请求。
 */
@Data
@Schema(description = "拒绝售后请求")
public class RejectRequest {

    @Schema(description = "拒绝原因", example = "商品不影响二次销售")
    @NotBlank(message = "请填写拒绝原因")
    private String reason;
}
