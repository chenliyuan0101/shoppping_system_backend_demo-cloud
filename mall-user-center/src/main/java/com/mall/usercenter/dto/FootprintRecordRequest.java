package com.mall.usercenter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 记录浏览足迹请求(登录用户在商品详情页触发)。
 */
@Data
@Schema(description = "记录足迹请求")
public class FootprintRecordRequest {

    @Schema(description = "商品 SPU ID", example = "1001")
    private Long spuId;
}
