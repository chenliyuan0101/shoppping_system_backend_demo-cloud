package com.mall.usercenter.dto;

import lombok.Data;

/**
 * 购物车列表项(带商品现价快照信息)。
 */
@Data
public class CartItemVO {

    private Long itemId;
    private Long skuId;
    private Long spuId;
    private String title;
    /** 规格文本，如 "黑 / 256G" */
    private String skuName;
    private String image;
    /** 现价(分) */
    private Long price;
    private Integer quantity;
    private Boolean checked;
    private Integer stock;
    /** true=商品已下架或SKU失效(置灰，可移除) */
    private Boolean invalid;
}
