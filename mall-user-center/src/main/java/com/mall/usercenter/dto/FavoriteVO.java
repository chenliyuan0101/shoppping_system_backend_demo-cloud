package com.mall.usercenter.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收藏列表项(带商品现价)。
 */
@Data
public class FavoriteVO {

    private Long spuId;
    private String title;
    private String mainImage;
    /** 最低 SKU 价(分) */
    private Long price;
    private Integer sales;
    private LocalDateTime createTime;
}
