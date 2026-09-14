package com.mall.usercenter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 未读消息数(前台角标，字段名与原 Map 键一致)。
 */
@Data
@Schema(description = "未读消息数")
public class NotificationUnreadVO {

    @Schema(description = "未读条数", example = "3")
    private long unread;
}
