package com.mall.trade.oms.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单明细(下单时快照)，对应 oms_order_item。
 */
@Data
@TableName("oms_order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long spuId;

    private Long skuId;

    private String spuTitle;

    /** 规格文本快照，如 "黑 / 256G" */
    private String skuName;

    private String skuImage;

    /** 成交单价(分，快照) */
    private Long price;

    private Integer quantity;

    private Long totalAmount;

    /**
     * 评价状态 0未评价 1已评价。
     *
     * <p><b>P4 批次 3 起本字段只映射列、不再被写入</b>（列按方案要求保留，不删列不删字段）：
     * 评价的属主已经搬到 {@code mall-review}，"这条明细评价过没有"由 review 自己的
     * {@code review_pending_item.commented} 表达。本字段因此恒为 DB 默认值 {@code 0}
     * （MyBatis-Plus 的字段策略是"null 不写"，没人 set 就不会出现在 INSERT 里）。
     * 保留它的意义只有一个：老代码/排查 SQL 读这列时不会因为字段消失而误解表结构。
     */
    private Integer commentStatus;

    private LocalDateTime createTime;
}
