package com.mall.product.service;

import com.mall.product.support.dto.CategoryNode;
import com.mall.product.support.dto.ProductListItemVO;
import com.mall.product.support.PageResult;
import com.mall.product.domain.Brand;
import com.mall.product.dto.AdminProductSaveRequest;
import com.mall.product.dto.CategorySaveRequest;
import com.mall.product.dto.ProductDetailVO;

import java.util.List;

/**
 * 后台商品域服务：类目管理 + 商品(SPU/SKU/详情)管理 + 品牌管理。
 */
public interface AdminProductService {

    // ---------- 类目 ----------
    List<CategoryNode> categoryTree();

    Long createCategory(CategorySaveRequest request);

    void updateCategory(Long id, CategorySaveRequest request);

    void updateCategoryStatus(Long id, Integer status);

    void deleteCategory(Long id);

    // ---------- 商品 ----------
    PageResult<ProductListItemVO> pageProducts(String keyword, Long categoryId, Long brandId,
                                               Integer status, Long minPrice, Long maxPrice,
                                               long pageNum, long pageSize);

    ProductDetailVO getProductDetail(Long spuId);

    Long createProduct(AdminProductSaveRequest request);

    void updateProduct(Long spuId, AdminProductSaveRequest request);

    void updateProductStatus(Long spuId, Integer status);

    void deleteProduct(Long spuId);

    // ---------- 品牌 ----------
    PageResult<Brand> pageBrands(String keyword, Integer status, long pageNum, long pageSize);

    /** 启用的品牌全量(下拉选择用) */
    List<Brand> listEnabledBrands();

    Long createBrand(String name, String logo, Integer sort, Integer status);

    void updateBrand(Long id, String name, String logo, Integer sort, Integer status);

    void deleteBrand(Long id);
}
