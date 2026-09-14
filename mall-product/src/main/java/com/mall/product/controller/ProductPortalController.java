package com.mall.product.controller;

import com.mall.product.support.ApiResponse;
import com.mall.product.support.dto.CategoryNode;
import com.mall.product.support.dto.ProductListItemVO;
import com.mall.product.support.PageQuery;
import com.mall.product.support.PageResult;
import com.mall.product.domain.Brand;
import com.mall.product.dto.ProductDetailVO;
import com.mall.product.service.ProductPortalService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 前台商品浏览(公开，游客可用，见《接口文档.md》2.3)。供 vue-web 商城使用。
 *
 * <p><b>P4 批次 3：{@code GET /api/product/{spuId}/comments} 已搬去 {@code mall-review}</b>
 * （{@code ProductCommentController}，由网关按精确路径 {@code /api/product/{spuId}/comments} 路由过去）。
 * 本类剩下的都是商品自己的能力；商品域的其它路径（列表/详情/品牌/类目）整体仍然落在单体，
 * 只有"评论"那一个子路径改投评价服务。
 */
@Tag(name = "前台-商品浏览")
@RestController
@RequiredArgsConstructor
public class ProductPortalController {

    private final ProductPortalService productPortalService;

    @GetMapping("/api/category/tree")
    public ApiResponse<List<CategoryNode>> categoryTree() {
        return ApiResponse.ok(productPortalService.enabledCategoryTree());
    }

    @GetMapping("/api/product/brands")
    public ApiResponse<List<Brand>> brands() {
        return ApiResponse.ok(productPortalService.enabledBrands());
    }

    @GetMapping("/api/product/page")
    public ApiResponse<PageResult<ProductListItemVO>> page(
            @Parameter(description = "标题关键字", example = "耳机") @RequestParam(required = false) String keyword,
            @Parameter(description = "类目ID(一级类目自动含子类)", example = "1") @RequestParam(required = false) Long categoryId,
            @Parameter(description = "品牌ID", example = "1") @RequestParam(required = false) Long brandId,
            @Parameter(description = "最低价(分)", example = "5000") @RequestParam(required = false) Long minPrice,
            @Parameter(description = "最高价(分)", example = "30000") @RequestParam(required = false) Long maxPrice,
            @Parameter(description = "排序 default|sales|priceAsc|priceDesc|newest", example = "default")
            @RequestParam(required = false) String sort,
            @ParameterObject PageQuery page) {
        return ApiResponse.ok(productPortalService.pageOnShelf(
                keyword, categoryId, brandId, minPrice, maxPrice, sort, page.getPageNum(), page.getPageSize()));
    }

    @GetMapping("/api/product/{spuId}")
    public ApiResponse<ProductDetailVO> detail(
            @Parameter(description = "商品 SPU ID", example = "1001") @PathVariable Long spuId) {
        return ApiResponse.ok(productPortalService.detailOnShelf(spuId));
    }
}
