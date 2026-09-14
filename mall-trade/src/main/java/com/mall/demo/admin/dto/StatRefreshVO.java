package com.mall.demo.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 订单统计手动重算结果(字段名与原 Map 键一一对应)。
 */
@Data
@Schema(description = "订单统计重算结果")
public class StatRefreshVO {

    /** 重算的日期 yyyy-MM-dd */
    private String date;

    /** 是否重算成功 */
    private boolean refreshed;
}
