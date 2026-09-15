package com.mall.usercenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import com.mall.common.support.MemberId;

/**
 * 购物车条目，对应 ums_cart_item(member_id+sku_id 唯一)。
 */
@Data
@TableName("ums_cart_item")
public class CartItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    /** SPU ID(冗余，列表展示) */
    private Long spuId;

    private Long skuId;

    private Integer quantity;

    /** 0未勾选 1勾选 */
    private Integer checked;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
