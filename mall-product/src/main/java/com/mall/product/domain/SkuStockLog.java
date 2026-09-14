package com.mall.product.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存变动流水，对应 pms_sku_stock_log。
 * change_type: 1下单扣减 2用户取消回补 3超时取消回补 4退款回补 5手动调整
 */
@Data
@TableName("pms_sku_stock_log")
public class SkuStockLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skuId;

    private String orderNo;

    private Integer changeType;

    /** 变动数量(正=增加 负=扣减) */
    private Integer delta;

    private Integer beforeStock;

    private Integer afterStock;

    private Long operatorId;

    private String remark;

    private LocalDateTime createTime;
}
