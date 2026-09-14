package com.mall.usercenter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 收藏开关结果(字段名与原 Map 键一致)。
 */
@Data
@Schema(description = "收藏/取消收藏结果")
public class FavoriteToggleVO {

    /** 本次操作后是否已收藏 */
    @Schema(description = "true=已收藏 false=已取消收藏", example = "true")
    private boolean favorited;
}
