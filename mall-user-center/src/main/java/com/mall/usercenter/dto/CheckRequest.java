package com.mall.usercenter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 勾选/全选请求。
 */
@Data
@Schema(description = "勾选状态请求")
public class CheckRequest {

    @Schema(description = "是否勾选", example = "true")
    private Boolean checked;
}
