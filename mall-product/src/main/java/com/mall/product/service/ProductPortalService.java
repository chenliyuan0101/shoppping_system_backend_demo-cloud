package com.mall.product.service;

import com.mall.product.support.dto.CategoryNode;
import com.mall.product.support.dto.ProductListItemVO;
import com.mall.product.support.PageResult;
import com.mall.product.domain.Brand;
import com.mall.product.dto.ProductDetailVO;

import java.util.List;

/**
 * 前台商品门户服务(公开，游客可访问，见《接口文档.md》2.3)。
 *
 * <p><b>P4 批次 3：{@code comments(spuId,...)} 已随评价域一起搬去 {@code mall-review}</b>
 * （端点 {@code GET /api/product/{spuId}/comments} 由网关精确路由过去）。
 * 本接口因此只剩"商品"本身的能力：评价不再住在商品域——同一份评价数据的读写只有
 * 一个属主（review），这是 P4 要的结果，也是"同一页面两个数字"这个风险的根治办法。
 */
public interface ProductPortalService {

    /** 类目树(仅启用) */
    List<CategoryNode> enabledCategoryTree();

    /** 启用品牌(筛选下拉) */
    List<Brand> enabledBrands();

    /** 货架商品分页：仅上架；支持关键字/类目(含子类)/品牌/价格区间/排序 */
    PageResult<ProductListItemVO> pageOnShelf(String keyword, Long categoryId, Long brandId,
                                              Long minPrice, Long maxPrice, String sort,
                                              long pageNum, long pageSize);

    /** 上架商品详情(下架/删除 → 404) */
    ProductDetailVO detailOnShelf(Long spuId);
}
