package com.mall.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.product.support.constant.EnableStatus;
import com.mall.product.support.dto.HomeFeedVO;
import com.mall.product.support.dto.ProductListItemVO;
import com.mall.product.support.dto.SkuSnapshotVO;
import com.mall.product.support.dto.SpuSnapshotVO;
import com.mall.product.domain.Sku;
import com.mall.product.domain.Spu;
import com.mall.product.mapper.SkuMapper;
import com.mall.product.mapper.SpuMapper;
import com.mall.product.service.ProductPortalService;
import com.mall.product.service.ProductQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品查询契约实现：只做"读自己的表 + 转契约快照"。
 *
 * <p>刻意保持"薄"：不放业务规则、不改状态。这样将来即使被 HTTP 化，
 * 也只是把方法体换成一次远程调用，没有逻辑需要跟着搬。
 */
@Service
@RequiredArgsConstructor
public class ProductQueryServiceImpl implements ProductQueryService {

    /** 首页每个区块的条数：与改造前 {@code HomeServiceImpl.SECTION_SIZE} 一致 */
    private static final int HOME_SECTION_SIZE = 8;

    /** 首页区块条数上限：调用方传参不可信，超出即回落默认值 */
    private static final int HOME_SECTION_MAX = 50;

    private final SkuMapper skuMapper;
    private final SpuMapper spuMapper;
    /** 首页区块复用门户货架逻辑（同一套"仅上架"口径），本类只做形状适配 */
    private final ProductPortalService productPortalService;

    @Override
    @Transactional(readOnly = true)
    public SkuSnapshotVO sku(Long skuId) {
        if (skuId == null) {
            return null;
        }
        Sku sku = skuMapper.selectById(skuId);
        return sku == null ? null : toSkuSnapshot(sku);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkuSnapshotVO> skus(Collection<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        return skuMapper.selectBatchIds(skuIds).stream()
                .map(ProductQueryServiceImpl::toSkuSnapshot)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SpuSnapshotVO spu(Long spuId) {
        if (spuId == null) {
            return null;
        }
        Spu spu = spuMapper.selectById(spuId);
        return spu == null ? null : toSpuSnapshot(spu);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SpuSnapshotVO> spus(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return List.of();
        }
        return spuMapper.selectBatchIds(spuIds).stream()
                .map(ProductQueryServiceImpl::toSpuSnapshot)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> minEnabledSkuPrices(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return Map.of();
        }
        return skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                        .in(Sku::getSpuId, spuIds)
                        .eq(Sku::getStatus, EnableStatus.ENABLED))
                .stream()
                .filter(s -> s.getPrice() != null)
                // 同一 SPU 多个 SKU 取最低价；没有可用 SKU 的 SPU 自然不出现在结果里
                .collect(Collectors.toMap(Sku::getSpuId, Sku::getPrice, Math::min));
    }

    @Override
    @Transactional(readOnly = true)
    public HomeFeedVO homeFeed(int size) {
        int limit = (size <= 0 || size > HOME_SECTION_MAX) ? HOME_SECTION_SIZE : size;
        // 这里刻意只做"三次门户读 → 一个快照"的装配，不含任何筛选规则：
        // 将来本方法被 HTTP 化时，搬走的是调用点，不是业务规则（§2.8 的设计约束）。
        return HomeFeedVO.builder()
                .categories(productPortalService.enabledCategoryTree())
                .hotProducts(onShelf("sales", limit))
                .newProducts(onShelf("newest", limit))
                .build();
    }

    /** 首页区块的货架读：无筛选条件，排序即"热门(sales)/新品(newest)" */
    private List<ProductListItemVO> onShelf(String sort, int limit) {
        return productPortalService.pageOnShelf(null, null, null, null, null, sort, 1, limit).getList();
    }

    private static SkuSnapshotVO toSkuSnapshot(Sku sku) {
        return new SkuSnapshotVO(sku.getId(), sku.getSpuId(), sku.getPrice(), sku.getOriginalPrice(),
                sku.getImage(), sku.getSpecValues(), sku.getStock(), sku.getStatus());
    }

    /** SPU 实体 → 契约快照。包内共享（{@code ProductStatQueryServiceImpl} 的榜单也用它），故非 private */
    static SpuSnapshotVO toSpuSnapshot(Spu spu) {
        return new SpuSnapshotVO(spu.getId(), spu.getTitle(), spu.getSubtitle(),
                spu.getMainImage(), spu.getSales(), spu.getStatus());
    }
}
