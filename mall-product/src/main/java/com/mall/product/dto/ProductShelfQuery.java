package com.mall.product.dto;

import java.util.List;

/**
 * 前台货架查询条件(交给 SpuMapper 的 countShelf/pageShelf 在 SQL 内使用)。
 */
public class ProductShelfQuery {

    private String keyword;
    private List<Long> categoryIds;   // 一级类目已含子类；null=不限
    private Long brandId;
    private Long minPrice;            // 最低价下限(按该 SPU 全部启用 SKU 的最低售价)
    private Long maxPrice;            // 最低价上限
    private String sort;              // default|sales|priceAsc|priceDesc|newest(白名单，映射见 SpuMapper)

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public List<Long> getCategoryIds() {
        return categoryIds;
    }

    public void setCategoryIds(List<Long> categoryIds) {
        this.categoryIds = categoryIds;
    }

    public Long getBrandId() {
        return brandId;
    }

    public void setBrandId(Long brandId) {
        this.brandId = brandId;
    }

    public Long getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(Long minPrice) {
        this.minPrice = minPrice;
    }

    public Long getMaxPrice() {
        return maxPrice;
    }

    public void setMaxPrice(Long maxPrice) {
        this.maxPrice = maxPrice;
    }

    public String getSort() {
        return sort;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }
}
