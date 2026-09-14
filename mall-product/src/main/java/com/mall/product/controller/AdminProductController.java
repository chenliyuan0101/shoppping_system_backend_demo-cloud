package com.mall.product.controller;

import com.mall.product.support.ApiResponse;
import com.mall.product.support.dto.ProductListItemVO;
import com.mall.product.support.dto.StatusRequest;
import com.mall.product.support.PageQuery;
import com.mall.product.support.PageResult;
import com.mall.product.dto.AdminProductSaveRequest;
import com.mall.product.dto.ProductDetailVO;
import com.mall.product.service.AdminProductService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台商品管理 /api/admin/product(见《接口文档.md》3.4，全量接口需管理员登录)。
 * 请求/参数示例由 OpenApiConfig 的 OpenApiCustomizer 统一注入。
 */
@Tag(name = "后台-商品管理")
@RestController
@RequestMapping("/api/admin/product")
@RequiredArgsConstructor
public class AdminProductController {

    private final AdminProductService adminProductService;

    @GetMapping("/page")
    public ApiResponse<PageResult<ProductListItemVO>> page(
            @Parameter(description = "标题关键字", example = "耳机") @RequestParam(required = false) String keyword,
            @Parameter(description = "类目ID(选一级类目含其子类)", example = "12") @RequestParam(required = false) Long categoryId,
            @Parameter(description = "品牌ID", example = "1") @RequestParam(required = false) Long brandId,
            @Parameter(description = "状态 0下架 1上架", example = "1") @RequestParam(required = false) Integer status,
            @Parameter(description = "最低价下限(单位:分)", example = "10000") @RequestParam(required = false) Long minPrice,
            @Parameter(description = "最低价上限(单位:分)", example = "30000") @RequestParam(required = false) Long maxPrice,
            @ParameterObject PageQuery page) {
        return ApiResponse.ok(adminProductService.pageProducts(
                keyword, categoryId, brandId, status, minPrice, maxPrice, page.getPageNum(), page.getPageSize()));
    }

    @GetMapping("/{spuId}")
    public ApiResponse<ProductDetailVO> detail(
            @Parameter(description = "商品 SPU ID", example = "1001") @PathVariable Long spuId) {
        return ApiResponse.ok(adminProductService.getProductDetail(spuId));
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody AdminProductSaveRequest request) {
        return ApiResponse.ok(adminProductService.createProduct(request));
    }

    @PutMapping("/{spuId}")
    public ApiResponse<Void> update(@Parameter(description = "商品 SPU ID", example = "1001")
                                    @PathVariable Long spuId,
                                    @RequestBody AdminProductSaveRequest request) {
        adminProductService.updateProduct(spuId, request);
        return ApiResponse.ok();
    }

    @PutMapping("/{spuId}/status")
    public ApiResponse<Void> updateStatus(@Parameter(description = "商品 SPU ID", example = "1001")
                                          @PathVariable Long spuId,
                                          @RequestBody StatusRequest request) {
        adminProductService.updateProductStatus(spuId, request.getStatus());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{spuId}")
    public ApiResponse<Void> delete(@Parameter(description = "商品 SPU ID", example = "1001")
                                    @PathVariable Long spuId) {
        adminProductService.deleteProduct(spuId);
        return ApiResponse.ok();
    }
}
