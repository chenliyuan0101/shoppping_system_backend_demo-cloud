package com.mall.usercenter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 单条消息标记已读的结果(字段名与原 Map 键一致)。
 */
@Data
@Schema(description = "单条消息标记已读结果")
public class NotificationReadVO {

    /** true=本次确实标记成功(不存在/不属于自己/本就已读 → false) */
    @Schema(description = "true=标记成功 false=未标记", example = "true")
    private boolean updated;
}
