package com.mall.product.dto;

import java.util.List;

/**
 * 后台商品列表查询条件(交给 SpuMapper 的 countAdminProducts/pageAdminProducts 在 SQL 内使用)。
 */
public class AdminProductQuery {

    private String keyword;           // 标题关键字
    private List<Long> categoryIds;   // 选一级类目时含其子类；null=不限
    private Long brandId;
    private Integer status;           // 0 下架 / 1 上架 / null 不限
    private Long minPrice;            // 最低价下限(按该 SPU 全部 SKU 的最低售价)
    private Long maxPrice;            // 最低价上限

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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
}
