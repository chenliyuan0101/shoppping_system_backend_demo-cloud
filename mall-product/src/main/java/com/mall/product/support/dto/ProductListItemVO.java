package com.mall.product.support.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品列表项(后台分页/前台货架通用，含最低价/总库存聚合)。
 */
@Data
public class ProductListItemVO {

    private Long spuId;
    private String title;
    private String subtitle;
    private String mainImage;
    private Long categoryId;
    private Long brandId;
    private String brandName;
    private Integer status;
    private Integer sales;
    /** 最低 SKU 价(分) */
    private Long minPrice;
    /** 总库存 */
    private Integer totalStock;
    private LocalDateTime createTime;
}
