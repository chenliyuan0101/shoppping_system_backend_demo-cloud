package com.mall.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.product.support.BusinessException;
import com.mall.product.support.RequestValidator;
import com.mall.product.support.PageKit;
import com.mall.product.support.CacheKeys;
import com.mall.product.support.CacheService;
import com.mall.product.support.JsonKit;
import com.mall.product.support.PageResult;
import com.mall.product.support.constant.EnableStatus;
import com.mall.product.support.constant.YesNo;
import com.mall.product.domain.Brand;
import com.mall.product.domain.Category;
import com.mall.product.domain.Sku;
import com.mall.product.domain.Spu;
import com.mall.product.domain.SpuDetail;
import com.mall.product.dto.AdminProductQuery;
import com.mall.product.dto.AdminProductSaveRequest;
import com.mall.product.dto.AdminProductSaveRequest.SkuItem;
import com.mall.product.support.dto.CategoryNode;
import com.mall.product.dto.CategorySaveRequest;
import com.mall.product.dto.ProductDetailVO;
import com.mall.product.support.dto.ProductListItemVO;
import com.mall.product.mapper.BrandMapper;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.SkuMapper;
import com.mall.product.mapper.SpuDetailMapper;
import com.mall.product.mapper.SpuMapper;
import com.mall.product.service.AdminProductService;
import com.mall.product.service.ProductSearchService;
import com.mall.product.support.CategoryScopeResolver;
import com.mall.product.support.ProductListAssembler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import com.mall.product.support.BrandSupport;
import com.mall.product.support.CategoryTreeBuilder;
import com.mall.product.support.TxCallbacks;

/**
 * 后台类目/商品管理实现。
 * 说明：分页未引入 MP 分页插件(jsqlparser)，使用 count + LIMIT 手动分页，数据量小场景足够；
 * 规格 SKU 采用"全量提交、更新时物理重建"的简化模型。
 */
@Service
@RequiredArgsConstructor
public class AdminProductServiceImpl implements AdminProductService {

    private final CategoryMapper categoryMapper;
    private final BrandMapper brandMapper;
    private final SpuMapper spuMapper;
    private final SpuDetailMapper spuDetailMapper;
    private final SkuMapper skuMapper;
    /** 共享支撑组件：类目范围解析 + 商品列表装配(与前台货架复用同一套实现) */
    private final CategoryScopeResolver categoryScopeResolver;
    private final ProductListAssembler productListAssembler;
    /** 写操作后清理前台读缓存 */
    private final CacheService cacheService;
    /** 商品写操作后同步检索索引(单条 upsert/delete；失败只告警，不阻塞业务) */
    private final ProductSearchService productSearchService;
    /** 请求参数校验(约束注解写在 DTO 字段上，此处统一触发) */
    private final RequestValidator requestValidator;

    /** 缺图时的占位图(与 pms_spu.main_image / pms_sku.image 的 DB 默认值保持一致) */
    @Value("${mall.image.placeholder-url:}")
    private String placeholderImage;

    // ==================== 类目 ====================

    @Override
    @Transactional(readOnly = true)
    public List<CategoryNode> categoryTree() {
        List<Category> all = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .orderByAsc(Category::getSort).orderByAsc(Category::getId));
        return CategoryTreeBuilder.build(all);
    }



    @Override
    @Transactional
    public Long createCategory(CategorySaveRequest request) {
        requestValidator.check(request);
        Long parentId = request.getParentId() == null ? 0L : request.getParentId();
        if (parentId != 0) {
            Category parent = categoryMapper.selectById(parentId);
            if (parent == null) {
                throw new BusinessException(404, "父类目不存在");
            }
            if (parent.getParentId() != null && parent.getParentId() != 0) {
                throw new BusinessException(400, "仅支持两级类目，不能在三级下新增");
            }
        }
        Category category = new Category();
        category.setParentId(parentId);
        category.setName(request.getName().trim());
        category.setSort(request.getSort() == null ? 0 : request.getSort());
        category.setStatus(EnableStatus.ENABLED);
        categoryMapper.insert(category);
        evictPortalCache(null);
        return category.getId();
    }

    @Override
    public void updateCategory(Long id, CategorySaveRequest request) {
        Category category = requireCategory(id);
        if (StringUtils.hasText(request.getName())) {
            category.setName(request.getName().trim());
        }
        if (request.getSort() != null) {
            category.setSort(request.getSort());
        }
        if (request.getParentId() != null) {
            category.setParentId(request.getParentId());
        }
        categoryMapper.updateById(category);
        evictPortalCache(null);
    }

    @Override
    public void updateCategoryStatus(Long id, Integer status) {
        requireCategory(id);
        if (status == null || (status != EnableStatus.DISABLED && status != EnableStatus.ENABLED)) {
            throw new BusinessException(400, "状态值仅支持 0/1");
        }
        Category update = new Category();
        update.setId(id);
        update.setStatus(status);
        categoryMapper.updateById(update);
        evictPortalCache(null);
    }

    @Override
    public void deleteCategory(Long id) {
        requireCategory(id);
        Long children = categoryMapper.selectCount(new LambdaQueryWrapper<Category>()
                .eq(Category::getParentId, id));
        if (children != null && children > 0) {
            throw new BusinessException(409, "请先删除子类目");
        }
        Long products = spuMapper.selectCount(new LambdaQueryWrapper<Spu>()
                .eq(Spu::getCategoryId, id));
        if (products != null && products > 0) {
            throw new BusinessException(409, "该类目下存在商品，无法删除");
        }
        categoryMapper.deleteById(id);
        evictPortalCache(null);
    }

    private Category requireCategory(Long id) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(404, "类目不存在");
        }
        return category;
    }

    // ==================== 商品 ====================

    @Override
    @Transactional(readOnly = true)
    public PageResult<ProductListItemVO> pageProducts(String keyword, Long categoryId, Long brandId,
                                                      Integer status, Long minPrice, Long maxPrice,
                                                      long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, 100);

        AdminProductQuery query = new AdminProductQuery();
        query.setKeyword(StringUtils.hasText(keyword) ? keyword : null);
        query.setCategoryIds(categoryScopeResolver.resolve(categoryId, false));
        query.setBrandId(brandId);
        query.setStatus(status);
        query.setMinPrice(minPrice);
        query.setMaxPrice(maxPrice);

        // 条件 + count + 排序 + 分页全部封装在 SpuMapper.countAdminProducts/pageAdminProducts(DB 内完成)
        Long totalObj = spuMapper.countAdminProducts(query);
        long total = totalObj == null ? 0 : totalObj;
        if (total == 0) {
            return PageResult.of(0, page, size, List.of());
        }
        List<Spu> pageList = spuMapper.pageAdminProducts(query, (page - 1) * size, size);
        return PageResult.of(total, page, size, productListAssembler.assemble(pageList));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDetailVO getProductDetail(Long spuId) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BusinessException(404, "商品不存在");
        }
        SpuDetail detail = spuDetailMapper.selectOne(new LambdaQueryWrapper<SpuDetail>()
                .eq(SpuDetail::getSpuId, spuId));

        ProductDetailVO vo = new ProductDetailVO();
        vo.setSpuId(spu.getId());
        vo.setCategoryId(spu.getCategoryId());
        Category category = categoryMapper.selectById(spu.getCategoryId());
        vo.setCategoryName(category == null ? null : category.getName());
        vo.setBrandId(spu.getBrandId());
        if (spu.getBrandId() != null) {
            Brand brand = brandMapper.selectById(spu.getBrandId());
            vo.setBrandName(brand == null ? null : brand.getName());
        }
        vo.setTitle(spu.getTitle());
        vo.setSubtitle(spu.getSubtitle());
        vo.setMainImage(spu.getMainImage());
        vo.setStatus(spu.getStatus());
        vo.setRecommended(spu.getRecommended());
        vo.setSales(spu.getSales());
        vo.setCreateTime(spu.getCreateTime());
        vo.setUpdateTime(spu.getUpdateTime());
        if (detail != null) {
            vo.setDescription(detail.getDescription());
            vo.setDetailHtml(detail.getDetailHtml());
            vo.setImages(JsonKit.toImageList(detail.getImages()));
            vo.setParams(JsonKit.toMapList(detail.getParams()));
        } else {
            vo.setImages(List.of());
            vo.setParams(List.of());
        }

        List<Sku> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getSpuId, spuId).orderByAsc(Sku::getId));
        vo.setSkus(skus.stream().map(ProductDetailVO.SkuVO::of).toList());
        return vo;
    }


    @Override
    @Transactional
    public Long createProduct(AdminProductSaveRequest request) {
        validateProduct(request);
        Spu spu = new Spu();
        fillSpu(spu, request);
        if (!StringUtils.hasText(spu.getMainImage()) && StringUtils.hasText(placeholderImage)) {
            spu.setMainImage(placeholderImage);   // 未传主图 → 用占位图(缺配置时留 null，由 DB 默认值兜底)
        }
        spu.setStatus(EnableStatus.DISABLED);      // 新增默认下架，后台审核后上架
        spu.setRecommended(YesNo.NO);
        spu.setSales(0);
        spuMapper.insert(spu);
        saveDetail(spu.getId(), request);
        saveSkus(spu.getId(), request.getSkus(), Map.of());   // 新增：无旧 SKU 可继承
        evictPortalCache(spu.getId());
        afterCommitOrNow(() -> productSearchService.syncLater(spu.getId()));    // 新增默认下架 → 实际是"从索引移除"(无则无操作)；MQ 可用则异步。⚠️ 必须提交后（见 afterCommitOrNow）
        return spu.getId();
    }

    @Override
    @Transactional
    public void updateProduct(Long spuId, AdminProductSaveRequest request) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BusinessException(404, "商品不存在");
        }
        validateProduct(request);
        fillSpu(spu, request);
        spuMapper.updateById(spu);
        saveDetail(spuId, request);
        // 简化模型：SKU 全量重建（旧行先读出来，用于继承请求里没带的 image/sales）
        Map<String, Sku> previous = previousSkusByCode(spuId);
        skuMapper.delete(new LambdaQueryWrapper<Sku>().eq(Sku::getSpuId, spuId));
        saveSkus(spuId, request.getSkus(), previous);
        evictPortalCache(spuId);
        afterCommitOrNow(() -> productSearchService.syncLater(spuId));          // 标题/价格/库存等变化 → 重写索引文档。⚠️ 必须提交后
    }

    @Override
    public void updateProductStatus(Long spuId, Integer status) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BusinessException(404, "商品不存在");
        }
        if (status == null || (status != EnableStatus.DISABLED && status != EnableStatus.ENABLED)) {
            throw new BusinessException(400, "状态值仅支持 0下架 1上架");
        }
        Spu update = new Spu();
        update.setId(spuId);
        update.setStatus(status);
        spuMapper.updateById(update);
        evictPortalCache(spuId);
        afterCommitOrNow(() -> productSearchService.syncLater(spuId));          // 上架即入索引 / 下架即出索引。⚠️ 必须提交后
    }

    @Override
    @Transactional
    public void deleteProduct(Long spuId) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BusinessException(404, "商品不存在");
        }
        spuMapper.deleteById(spuId);   // 逻辑删除 SPU
        skuMapper.delete(new LambdaQueryWrapper<Sku>().eq(Sku::getSpuId, spuId));  // 逻辑删除其 SKU
        evictPortalCache(spuId);
        afterCommitOrNow(() -> productSearchService.syncLater(spuId));          // 逻辑删除后同步一次 → 移出索引。⚠️ 必须提交后：否则 search 回读到"还没删"的状态 ⇒ 幽灵文档（P6-4 窗口实测）
    }

    // ==================== 品牌 ====================

    @Override
    @Transactional(readOnly = true)
    public PageResult<Brand> pageBrands(String keyword, Integer status, long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, 100);
        LambdaQueryWrapper<Brand> wrapper = new LambdaQueryWrapper<Brand>()
                .like(StringUtils.hasText(keyword), Brand::getName, keyword)
                .eq(status != null, Brand::getStatus, status)
                .orderByAsc(Brand::getSort).orderByAsc(Brand::getId);
        long total = brandMapper.selectCount(wrapper);
        List<Brand> list = brandMapper.selectList(wrapper.last("LIMIT " + ((page - 1) * size) + "," + size));
        return PageResult.of(total, page, size, list);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Brand> listEnabledBrands() {
        return BrandSupport.enabledBrands(brandMapper);
    }

    @Override
    @Transactional
    public Long createBrand(String name, String logo, Integer sort, Integer status) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(400, "请输入品牌名称");
        }
        Brand brand = new Brand();
        brand.setName(name.trim());
        brand.setLogo(logo);
        brand.setSort(sort == null ? 0 : sort);
        brand.setStatus(status == null ? EnableStatus.ENABLED : status);
        brandMapper.insert(brand);
        evictPortalCache(null);
        return brand.getId();
    }

    @Override
    public void updateBrand(Long id, String name, String logo, Integer sort, Integer status) {
        Brand brand = brandMapper.selectById(id);
        if (brand == null) {
            throw new BusinessException(404, "品牌不存在");
        }
        if (StringUtils.hasText(name)) {
            brand.setName(name.trim());
        }
        if (logo != null) {
            brand.setLogo(logo);
        }
        if (sort != null) {
            brand.setSort(sort);
        }
        if (status != null) {
            if (status != EnableStatus.DISABLED && status != EnableStatus.ENABLED) {
                throw new BusinessException(400, "状态值仅支持 0/1");
            }
            brand.setStatus(status);
        }
        brandMapper.updateById(brand);
        evictPortalCache(null);
        if (StringUtils.hasText(name)) {
            // 品牌改名会影响索引文档里的 brandName → 重写该品牌下在架商品的索引
            afterCommitOrNow(() -> productSearchService.syncBrandLater(id));   // ⚠️ 同上：品牌改名也要提交后再同步，否则索引里仍是旧 brandName
        }
    }

    @Override
    public void deleteBrand(Long id) {
        Brand brand = brandMapper.selectById(id);
        if (brand == null) {
            throw new BusinessException(404, "品牌不存在");
        }
        Long products = spuMapper.selectCount(new LambdaQueryWrapper<Spu>()
                .eq(Spu::getBrandId, id));
        if (products != null && products > 0) {
            throw new BusinessException(409, "该品牌下存在商品，请先移除商品再删除");
        }
        brandMapper.deleteById(id);
        evictPortalCache(null);
    }

    // ---------- 私有 ----------

    /**
     * 写操作后失效前台读缓存：
     *  - 首页聚合/类目树/品牌列表：直接删固定 key
     *  - 商品详情：删该 spu 的 key
     *  - 货架分页：key 含筛选参数无法枚举 → 版本号 +1 让全部旧 key 立即失效(旧 key 由 45 秒 TTL 自然回收)
     */
    private void evictPortalCache(Long spuId) {
        // 必须等**事务提交后**再删缓存：
        // 若在事务内就删，删完到提交之间若有并发读，会把库里的旧值重新读出来写回缓存，
        // 结果"后台改完，前台在整个 TTL(45s/5min) 内还看到旧数据"；回滚时更不该删（库里的旧值本来是对的）。
        // 与 MQ 投递同一套纪律：有事务挂 afterCommit，没有事务(纯查询路径)直接执行。
        afterCommitOrNow(() -> {
            cacheService.delete(CacheKeys.homeIndex(), CacheKeys.categoryTree(), CacheKeys.brandList(),
                    spuId == null ? null : CacheKeys.productDetail(spuId));
            cacheService.increment(CacheKeys.productShelfVersion(), null);
        });
    }

    /**
     * 有事务就挂到 {@code afterCommit} 执行，没有事务立即执行。
     *
     * <p>⚠️ <b>P6-5：这个方法不只用于"删缓存"，也用于"同步索引"</b>——两者是同一类副作用，必须同一时点。
     * 起因是 P6-4 窗口里实测到的一个**真实缺陷（幽灵索引文档）**：
     * <pre>
     *   后台"逻辑删除商品" → 行内调 {@code syncLater} → search 侧**回读 product 的库**组装文档；
     *   若这次回读发生在**本地事务提交之前**，它读到的是"还没删"的状态 ⇒ 把文档保住/写回索引，
     *   提交后没有任何重试 ⇒ **已删除的商品仍能被搜到**（点进去详情 404）。
     *   实测：`_id=22136`（库里 status=1 deleted=1）留在索引里；product 的 index-docs 对它返回 docs:[]（内容供给是对的）、
     *   手动 sync 能删掉、`mall:es:pending` 为空（兜底未触发）⇒ 就是"提交前回读"的竞态；第 1 次复现没留幽灵、第 2 次留了 ⇒ 间歇性。
     * </pre>
     * <b>缓存失效原本就走 afterCommit（下面的注释解释了同样的理由），只有索引同步漏了</b> ⇒ 本批统一。
     *
     * <p>⚠️ P6-5 #1 起，实现搬到 {@link TxCallbacks}：索引同步多了一个同样必须"提交后"的调用方
     * （{@code ProductSyncPublisher} 投 MQ，被 {@code StockCommandServiceImpl} 在**事务内**调用）。
     * 保留本方法只为不动这 6 个调用点与它们的注释；逻辑一字未变（单一实现见 {@link TxCallbacks}）。
     */
    private static void afterCommitOrNow(Runnable action) {
        TxCallbacks.afterCommitOrNow(action);
    }

    private void validateProduct(AdminProductSaveRequest request) {
        if (request.getCategoryId() == null || categoryMapper.selectById(request.getCategoryId()) == null) {
            throw new BusinessException(400, "请选择有效的商品类目");
        }
        if (request.getBrandId() != null && brandMapper.selectById(request.getBrandId()) == null) {
            throw new BusinessException(400, "品牌不存在");
        }
        requestValidator.check(request);   // title 与 skus 的必填约束都写在 DTO 上
        // 主图不校验必填：缺图时统一用占位图(mall.image.placeholder-url / DB 默认值)
        long distinctCodes = request.getSkus().stream().map(SkuItem::getSkuCode).distinct().count();
        if (distinctCodes != request.getSkus().size()) {
            throw new BusinessException(400, "SKU 编码不能重复");
        }
        for (SkuItem item : request.getSkus()) {
            if (!StringUtils.hasText(item.getSkuCode())) {
                throw new BusinessException(400, "SKU 编码不能为空");
            }
            if (item.getPrice() == null || item.getPrice() <= 0) {
                throw new BusinessException(400, "SKU 价格必须大于 0");
            }
            if (item.getStock() == null || item.getStock() < 0) {
                throw new BusinessException(400, "SKU 库存不能为负");
            }
        }
    }

    private void fillSpu(Spu spu, AdminProductSaveRequest request) {
        spu.setCategoryId(request.getCategoryId());
        spu.setBrandId(request.getBrandId());
        spu.setTitle(request.getTitle().trim());
        spu.setSubtitle(request.getSubtitle());
        // 主图：空白一律转为 null —— 编辑时 MP 只更新非空字段(即"不修改主图")，
        // 新增时由 createProduct 兜底为占位图(或留给 DB 默认值)
        spu.setMainImage(StringUtils.hasText(request.getMainImage()) ? request.getMainImage() : null);
    }

    private void saveDetail(Long spuId, AdminProductSaveRequest request) {
        SpuDetail detail = spuDetailMapper.selectOne(new LambdaQueryWrapper<SpuDetail>()
                .eq(SpuDetail::getSpuId, spuId));
        if (detail == null) {
            detail = new SpuDetail();
            detail.setSpuId(spuId);
            detail.setDescription(request.getDescription());
            detail.setImages(JsonKit.toJson(request.getImages() == null ? List.of() : request.getImages()));
            detail.setParams(JsonKit.toJson(request.getParams() == null ? List.of() : request.getParams()));
            detail.setDetailHtml(request.getDetailHtml());
            spuDetailMapper.insert(detail);
        } else {
            detail.setDescription(request.getDescription());
            detail.setImages(JsonKit.toJson(request.getImages() == null ? List.of() : request.getImages()));
            detail.setParams(JsonKit.toJson(request.getParams() == null ? List.of() : request.getParams()));
            detail.setDetailHtml(request.getDetailHtml());
            spuDetailMapper.updateById(detail);
        }
    }

    /**
     * 保存 SKU（编辑商品时是"全量重建"）。
     *
     * <p>重建前先把旧 SKU 按 {@code skuCode} 读出来，用于**继承**请求里没带的字段：
     * <ul>
     *   <li>{@code image}：管理端表单目前不提交 SKU 图，以前会把已有图片全部清空（保存一次丢一次）</li>
     *   <li>{@code sales}：销量是展示/排序字段，重建时归零会让"按销量排序"错乱</li>
     * </ul>
     * 历史订单的 {@code sku_id} 无法保留（重建必然换主键），这是"简化模型"的已知代价，
     * 库存回补侧已改为绕过逻辑删除读取 SKU，不会再出现"订单退不了库存"的静默问题。
     */
    private void saveSkus(Long spuId, List<SkuItem> items, Map<String, Sku> previousByCode) {
        for (SkuItem item : items) {
            Sku previous = previousByCode.get(item.getSkuCode() == null ? "" : item.getSkuCode().trim());
            Sku sku = new Sku();
            sku.setSpuId(spuId);
            sku.setSkuCode(item.getSkuCode().trim());
            List<Map<String, Object>> specs = item.getSpecValues() == null
                    ? List.of() : item.getSpecValues().stream()
                    .map(p -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", p.getName());
                        m.put("value", p.getValue());
                        return m;
                    }).toList();
            sku.setSpecValues(JsonKit.toJson(specs));
            // 请求没带图 → 沿用旧图（而不是清空）
            String image = item.getImage();
            if ((image == null || image.isBlank()) && previous != null) {
                image = previous.getImage();
            }
            sku.setImage(image);
            sku.setPrice(item.getPrice());
            sku.setOriginalPrice(item.getOriginalPrice());
            sku.setStock(item.getStock());
            // 新品销量从 0 起；编辑同一 skuCode 时保留原销量
            sku.setSales(previous == null || previous.getSales() == null ? 0 : previous.getSales());
            sku.setStatus(EnableStatus.ENABLED);
            skuMapper.insert(sku);
        }
    }

    /** 旧 SKU 按 skuCode 建索引（保留 image/sales 用），逻辑删除的行也要读出来 */
    private Map<String, Sku> previousSkusByCode(Long spuId) {
        List<Sku> old = skuMapper.selectListIgnoreLogicDeleteBySpu(spuId);
        Map<String, Sku> map = new LinkedHashMap<>();
        for (Sku s : old) {
            if (s.getSkuCode() != null) {
                map.putIfAbsent(s.getSkuCode().trim(), s);
            }
        }
        return map;
    }
}
