package com.mall.usercenter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 全部标记已读的结果(返回影响条数，字段名与原 Map 键一致)。
 */
@Data
@Schema(description = "全部标记已读结果")
public class NotificationReadAllVO {

    /** 本次被标记为已读的条数 */
    @Schema(description = "本次标记为已读的条数", example = "5")
    private int updated;
}
