package com.mall.product.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 后台商品详情(SPU + 详情 + SKU 回显)。
 */
@Data
public class ProductDetailVO {

    private Long spuId;
    private Long categoryId;
    private String categoryName;
    private Long brandId;
    private String brandName;
    private String title;
    private String subtitle;
    private String mainImage;
    private Integer status;
    private Integer recommended;
    private Integer sales;
    private String description;
    private String detailHtml;
    private List<String> images;
    private List<Map<String, Object>> params;
    private List<SkuVO> skus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @Data
    public static class SkuVO {
        private Long skuId;
        private String skuCode;
        private List<Map<String, Object>> specValues;
        private String image;
        private Long price;
        private Long originalPrice;
        private Integer stock;
        private Integer status;

        /** SKU 实体 → VO(前台详情与后台详情共用，避免两处各写一份映射) */
        public static SkuVO of(com.mall.product.domain.Sku sku) {
            SkuVO vo = new SkuVO();
            vo.setSkuId(sku.getId());
            vo.setSkuCode(sku.getSkuCode());
            vo.setSpecValues(com.mall.product.support.JsonKit.toMapList(sku.getSpecValues()));
            vo.setImage(sku.getImage());
            vo.setPrice(sku.getPrice());
            vo.setOriginalPrice(sku.getOriginalPrice());
            vo.setStock(sku.getStock());
            vo.setStatus(sku.getStatus());
            return vo;
        }
    }
}
