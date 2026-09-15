package com.mall.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.product.support.BusinessException;
import com.mall.common.support.PageKit;
import com.mall.product.support.CacheKeys;
import com.mall.common.support.CacheService;
import com.mall.common.support.JsonKit;
import com.mall.product.support.PageResult;
import com.mall.product.support.constant.EnableStatus;
import com.mall.product.domain.Brand;
import com.mall.product.domain.Category;
import com.mall.product.domain.Sku;
import com.mall.product.domain.Spu;
import com.mall.product.domain.SpuDetail;
import com.mall.product.support.dto.CategoryNode;
import com.mall.product.dto.ProductDetailVO;
import com.mall.product.dto.ProductIdPage;
import com.mall.product.support.dto.ProductListItemVO;
import com.mall.product.dto.ProductShelfQuery;
import com.mall.product.mapper.BrandMapper;
import com.mall.product.mapper.CategoryMapper;
import com.mall.product.mapper.SkuMapper;
import com.mall.product.mapper.SpuDetailMapper;
import com.mall.product.mapper.SpuMapper;
import com.mall.product.service.ProductPortalService;
import com.mall.product.service.ProductSearchService;
import com.mall.product.support.CategoryScopeResolver;
import com.mall.product.support.ProductListAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import com.mall.product.support.BrandSupport;
import com.mall.product.support.CategoryTreeBuilder;

/**
 * 前台商品门户实现：全部仅展示 上架/启用 数据，不暴露下架与删除记录。
 *
 * <p><b>P4 批次 3：评价列表（{@code comments}）整体搬去 {@code mall-review}</b>。
 * 这里曾经是本项目里"读评价"的唯一实现（商品详情页的评价数/好评率/昵称都在这儿算），
 * 其中取昵称还要跨域问会员域。搬走后商品域**不再碰评价表**：
 * 评价的读写只有一个属主，商品详情页的评价区由网关路由到 review 读取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductPortalServiceImpl implements ProductPortalService {

    private static final long MAX_PAGE_SIZE = 50;   // 货架分页每页上限（评价列表的上限已随评价域搬去 mall-review）

    /** 类目树/品牌列表：低频变更 → 30 分钟 */
    private static final Duration CATEGORY_TTL = Duration.ofMinutes(30);
    private static final Duration BRAND_TTL = Duration.ofMinutes(30);
    /** 货架分页：商品改动由版本号即时失效，销量/库存靠 45 秒短 TTL 收敛 */
    private static final Duration SHELF_TTL = Duration.ofSeconds(45);
    /** 商品详情：SPU 基础信息/富文本 5 分钟；SKU 价格与库存命中后仍实时读 DB */
    private static final Duration DETAIL_TTL = Duration.ofMinutes(5);

    private final CategoryMapper categoryMapper;
    private final BrandMapper brandMapper;
    private final SpuMapper spuMapper;
    private final SpuDetailMapper spuDetailMapper;
    private final SkuMapper skuMapper;
    /** 共享支撑组件：类目范围解析 + 商品列表装配(与后台商品列表复用同一套实现) */
    private final CategoryScopeResolver categoryScopeResolver;
    private final ProductListAssembler productListAssembler;
    /** Redis 读缓存(不可用时自动降级为纯 DB) */
    private final CacheService cacheService;
    /** ES 关键字检索(不可用时本类会自动回落 MySQL LIKE 路径) */
    private final ProductSearchService productSearchService;

    // ==================== 类目/品牌 ====================

    @Override
    @Transactional(readOnly = true)
    public List<CategoryNode> enabledCategoryTree() {
        List<CategoryNode> cached = cacheService.getJson(CacheKeys.categoryTree(), new TypeReference<List<CategoryNode>>() {
        });
        if (cached != null) {
            return cached;
        }
        List<Category> all = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(Category::getStatus, EnableStatus.ENABLED)
                .orderByAsc(Category::getSort).orderByAsc(Category::getId));
        List<CategoryNode> tree = CategoryTreeBuilder.build(all);
        cacheService.setJson(CacheKeys.categoryTree(), tree, CATEGORY_TTL);
        return tree;
    }



    @Override
    @Transactional(readOnly = true)
    public List<Brand> enabledBrands() {
        List<Brand> cached = cacheService.getJson(CacheKeys.brandList(), new TypeReference<List<Brand>>() {
        });
        if (cached != null) {
            return cached;
        }
        List<Brand> brands = BrandSupport.enabledBrands(brandMapper);
        cacheService.setJson(CacheKeys.brandList(), brands, BRAND_TTL);
        return brands;
    }

    // ==================== 商品货架 ====================

    @Override
    @Transactional(readOnly = true)
    public PageResult<ProductListItemVO> pageOnShelf(String keyword, Long categoryId, Long brandId,
                                                     Long minPrice, Long maxPrice, String sort,
                                                     long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));

        String cacheKey = CacheKeys.productShelf(shelfVersion(), String.join("|",
                String.valueOf(keyword), String.valueOf(categoryId), String.valueOf(brandId),
                String.valueOf(minPrice), String.valueOf(maxPrice), String.valueOf(sort),
                String.valueOf(page), String.valueOf(size)));
        PageResult<ProductListItemVO> cached = cacheService.getJson(cacheKey, new TypeReference<PageResult<ProductListItemVO>>() {
        });
        if (cached != null) {
            return cached;
        }

        ProductShelfQuery query = new ProductShelfQuery();
        query.setKeyword(StringUtils.hasText(keyword) ? keyword : null);
        query.setCategoryIds(categoryScopeResolver.resolve(categoryId, true));
        query.setBrandId(brandId);
        query.setMinPrice(minPrice);
        query.setMaxPrice(maxPrice);
        query.setSort(StringUtils.hasText(sort) ? sort : "default");

        // 关键字检索走 ES(筛选/排序/分页/总数全部下推)；ES 不可用时自动回落下面的 MySQL 路径
        if (StringUtils.hasText(keyword)) {
            PageResult<ProductListItemVO> esResult = searchByEs(cacheKey, query, page, size);
            if (esResult != null) {
                return esResult;
            }
        }

        // 筛选 + count + 排序 + 分页全部封装在 SpuMapper.countShelf/pageShelf(DB 内完成)
        Long totalObj = spuMapper.countShelf(query);
        long total = totalObj == null ? 0 : totalObj;
        if (total == 0) {
            PageResult<ProductListItemVO> empty = PageResult.of(0, page, size, List.of());
            cacheService.setJson(cacheKey, empty, SHELF_TTL);
            return empty;
        }
        List<Spu> pageSpus = spuMapper.pageShelf(query, (page - 1) * size, size);
        PageResult<ProductListItemVO> result = PageResult.of(total, page, size, productListAssembler.assemble(pageSpus));
        cacheService.setJson(cacheKey, result, SHELF_TTL);
        return result;
    }

    /**
     * 关键字检索走 ES：ES 负责筛选/排序/分页/总数，拿到 spuId 后回表装配展示字段。
     *
     * @return 命中结果；ES 不可用/异常时返回 {@code null}，由调用方回落 MySQL LIKE 路径
     */
    private PageResult<ProductListItemVO> searchByEs(String cacheKey, ProductShelfQuery query, long page, long size) {
        try {
            ProductIdPage idPage = productSearchService.search(query.getKeyword(), query.getCategoryIds(),
                    query.getBrandId(), query.getMinPrice(), query.getMaxPrice(), query.getSort(), page, size);

            List<ProductListItemVO> items = idPage.spuIds().isEmpty()
                    ? List.of()
                    : orderByGivenIds(productListAssembler.assemble(spuMapper.selectBatchIds(idPage.spuIds())),
                            idPage.spuIds());

            PageResult<ProductListItemVO> result = PageResult.of(idPage.total(), page, size, items);
            cacheService.setJson(cacheKey, result, SHELF_TTL);
            return result;
        } catch (Exception e) {
            log.warn("ES 检索失败，降级为 MySQL LIKE：keyword={} err={}", query.getKeyword(), e.getMessage());
            return null;
        }
    }

    /** 回表结果按 ES 返回的顺序重排(selectBatchIds 不保证顺序)；MySQL 里已删除的跳过 */
    private List<ProductListItemVO> orderByGivenIds(List<ProductListItemVO> items, List<Long> orderedSpuIds) {
        Map<Long, ProductListItemVO> byId = items.stream()
                .collect(Collectors.toMap(ProductListItemVO::getSpuId, v -> v, (a, b) -> a));
        return orderedSpuIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    // ==================== 详情/评价 ====================

    @Override
    @Transactional(readOnly = true)
    public ProductDetailVO detailOnShelf(Long spuId) {
        ProductDetailVO cached = cacheService.getJson(CacheKeys.productDetail(spuId), ProductDetailVO.class);
        if (cached != null) {
            // 命中缓存：上下架状态与 SKU(价格/库存/规格)仍以 DB 为准——**库存绝不走缓存**
            Spu live = spuMapper.selectById(spuId);
            if (live == null || live.getStatus() == null || live.getStatus() != EnableStatus.ENABLED) {
                cacheService.delete(CacheKeys.productDetail(spuId));
                throw new BusinessException(404, "商品不存在或已下架");
            }
            cached.setSales(live.getSales());
            cached.setSkus(liveSkus(spuId));
            return cached;
        }
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null || spu.getStatus() == null || spu.getStatus() != EnableStatus.ENABLED) {
            throw new BusinessException(404, "商品不存在或已下架");
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
        vo.setSales(spu.getSales());
        if (detail != null) {
            vo.setDescription(detail.getDescription());
            vo.setDetailHtml(detail.getDetailHtml());
            vo.setImages(JsonKit.toImageList(detail.getImages()));
            vo.setParams(JsonKit.toMapList(detail.getParams()));
        } else {
            vo.setImages(List.of());
            vo.setParams(List.of());
        }

        vo.setSkus(liveSkus(spuId));
        cacheService.setJson(CacheKeys.productDetail(spuId), vo, DETAIL_TTL);
        return vo;
    }

    /** 在架 SKU 实时读取(详情缓存命中时用于回填价格/库存) */
    private List<ProductDetailVO.SkuVO> liveSkus(Long spuId) {
        return skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getSpuId, spuId).eq(Sku::getStatus, EnableStatus.ENABLED).orderByAsc(Sku::getId))
                .stream().map(ProductDetailVO.SkuVO::of).toList();
    }

    /** 货架缓存版本号(Redis 不可用 → 0，退化为直接用 DB) */
    private long shelfVersion() {
        String raw = cacheService.get(CacheKeys.productShelfVersion());
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
