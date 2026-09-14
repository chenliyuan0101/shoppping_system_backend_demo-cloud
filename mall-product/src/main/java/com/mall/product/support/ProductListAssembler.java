package com.mall.product.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.product.support.dto.ProductListItemVO;
import com.mall.product.domain.Brand;
import com.mall.product.domain.Sku;
import com.mall.product.domain.Spu;
import com.mall.product.mapper.BrandMapper;
import com.mall.product.mapper.SkuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商品列表项装配(前台货架与后台商品列表共用)：
 * 对"当前页"的 SPU 批量取 SKU 与品牌(各一次查询，避免逐条 N+1)，
 * 计算最低价/总库存并回填品牌名，输出统一的 ProductListItemVO。
 */
@Component
@RequiredArgsConstructor
public class ProductListAssembler {

    private final SkuMapper skuMapper;
    private final BrandMapper brandMapper;

    public List<ProductListItemVO> assemble(List<Spu> spus) {
        if (spus == null || spus.isEmpty()) {
            return List.of();
        }
        List<Long> spuIds = spus.stream().map(Spu::getId).toList();
        Map<Long, List<Sku>> skuBySpu = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                        .in(Sku::getSpuId, spuIds).orderByAsc(Sku::getId))
                .stream().collect(Collectors.groupingBy(Sku::getSpuId));

        List<Long> brandIds = spus.stream().map(Spu::getBrandId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> brandNames = brandIds.isEmpty() ? Map.of()
                : brandMapper.selectBatchIds(brandIds).stream()
                .collect(Collectors.toMap(Brand::getId, Brand::getName));

        return spus.stream().map(spu -> {
            List<Sku> skus = skuBySpu.getOrDefault(spu.getId(), List.of());
            long minPrice = skus.stream().filter(s -> s.getPrice() != null)
                    .mapToLong(Sku::getPrice).min().orElse(0);
            int totalStock = skus.stream().mapToInt(s -> s.getStock() == null ? 0 : s.getStock()).sum();

            ProductListItemVO vo = new ProductListItemVO();
            vo.setSpuId(spu.getId());
            vo.setTitle(spu.getTitle());
            vo.setSubtitle(spu.getSubtitle());
            vo.setMainImage(spu.getMainImage());
            vo.setCategoryId(spu.getCategoryId());
            vo.setBrandId(spu.getBrandId());
            vo.setBrandName(spu.getBrandId() == null ? null : brandNames.get(spu.getBrandId()));
            vo.setStatus(spu.getStatus());
            vo.setSales(spu.getSales());
            vo.setMinPrice(minPrice);
            vo.setTotalStock(totalStock);
            vo.setCreateTime(spu.getCreateTime());
            return vo;
        }).toList();
    }
}
