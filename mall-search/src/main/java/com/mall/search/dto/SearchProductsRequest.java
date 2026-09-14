package com.mall.search.dto;

import java.util.List;

/**
 * 检索请求（本服务的内部接口 {@code POST /internal/v1/search/products} 的请求体）。
 *
 * <p>字段口径与单体的 {@code ProductSearchService#search(...)} 参数**逐字对应**：
 * 调用方（P6-3 起是 product 的货架读路径）把已经展开好的条件传进来——
 * 特别是 {@code categoryIds}：**"含子类"的展开由调用方**（{@code CategoryScopeResolver}）做，
 * 本服务只做 terms 精确匹配（与现状一致，见 {@code ProductSearchServiceImpl#buildQuery}）。
 *
 * <p>{@code pageNum}/{@code pageSize} 在这里**夹取**（≤0 或超上限按默认值处理）：
 * 内部接口的入参同样不可信，一次请求不该把整个索引拖出来。
 */
public record SearchProductsRequest(String keyword,
                                    List<Long> categoryIds,
                                    Long brandId,
                                    Long minPrice,
                                    Long maxPrice,
                                    String sort,
                                    Long pageNum,
                                    Long pageSize) {

    /** 每页上限（与前台货架一致：50） */
    public static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 10;

    public SearchProductsRequest {
        categoryIds = categoryIds == null ? List.of() : List.copyOf(categoryIds);
    }

    /** 页码：≤0 → 1 */
    public long normalizedPageNum() {
        return pageNum == null || pageNum <= 0 ? 1L : pageNum;
    }

    /** 每页条数：≤0 → 10；> 50 → 50（不信任调用方传参） */
    public long normalizedPageSize() {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(MAX_PAGE_SIZE, pageSize);
    }
}
