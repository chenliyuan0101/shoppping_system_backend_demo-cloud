package com.mall.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.product.domain.Brand;
import com.mall.product.domain.Spu;
import com.mall.product.dto.IndexDocsResult;
import com.mall.product.dto.ProductSearchDoc;
import com.mall.product.dto.SkuAggregate;
import com.mall.product.mapper.BrandMapper;
import com.mall.product.mapper.SkuMapper;
import com.mall.product.mapper.SpuMapper;
import com.mall.product.service.ProductIndexDocService;
import com.mall.product.support.constant.EnableStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@link ProductIndexDocService} 的实现（**P6-2 新增**）。
 *
 * <h2>这个类里的每一行都是从单体的 {@code pms.ProductSearchServiceImpl} 搬过来的口径</h2>
 * 具体是它的三处"取数"与私有方法 {@code buildDoc(...)}——索引文档怎么拼是**商品域的知识**，
 * 所以留在商品域；检索域只负责"把文档写进 ES"。搬运时逐条对齐：
 * <pre>
 *  minPrice     = MIN(pms_sku.price)  WHERE spu_id=spuId AND deleted=0 AND status=1，无可用 SKU → 0
 *  totalStock   = SUM(pms_sku.stock)  同上的集合，无 → 0
 *  brandName    = pms_brand.name（brandId 为 null → null）
 *  sales/status/mainImage/subtitle/title/categoryId/spuId = pms_spu 同名列
 *  createTimeMillis = pms_spu.create_time 按 ZoneId.systemDefault() 换算
 * </pre>
 * ⇒ 与单体 {@code ProductSearchServiceImpl#buildDoc} **逐字相同**（含"null 时取 0"这些边界）。
 *
 * <h2>两处实现选择（都写清了理由，便于对照）</h2>
 * <ol>
 *   <li><b>聚合查询用"整表聚合 + 内存过滤"</b>（{@code SkuMapper#selectShelfAggregates}）而不是
 *       {@code selectShelfAggregateBySpu} 逐条查：两者口径完全相同（都是
 *       {@code deleted=0 AND status=1} 的 MIN/SUM），但前者一次查询就够，
 *       避免"一次批量同步 100 个 spu = 100 次查询"。
 *       单体那边正是"reindex 用整表聚合、syncProduct 用单条聚合"，本服务统一成前者（结果一致）。</li>
 *   <li><b>品牌名一次全量取</b>（{@code brandMapper.selectList(null)} 建 Map）而不是按 id 逐条查：
 *       与单体 {@code reindex} 的做法一致，避免 N+1。品牌表只有几十行。</li>
 * </ol>
 *
 * <p>⚠️ 只读：本类不写任何表（{@code @Transactional(readOnly = true)}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductIndexDocServiceImpl implements ProductIndexDocService {

    /** 分页模式下每页条数上限：不信任调用方传参（一次请求不该把 1508 个商品全拖出来） */
    private static final long MAX_PAGE_SIZE = 500;

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final BrandMapper brandMapper;

    @Override
    @Transactional(readOnly = true)
    public IndexDocsResult bySpuIds(Collection<Long> spuIds) {
        long total = countOnShelf();
        if (spuIds == null || spuIds.isEmpty()) {
            return new IndexDocsResult(total, List.of());
        }
        List<Long> ids = spuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return new IndexDocsResult(total, List.of());
        }
        // 与单体 syncProduct(spuId) 等价：查不到（含逻辑删除）或非在架 → **不产生文档**
        // （调用方会用"请求的 id − 返回的 id"把索引里那些文档删掉）
        List<Spu> spus = spuMapper.selectBatchIds(ids).stream()
                .filter(s -> s.getStatus() != null && s.getStatus() == EnableStatus.ENABLED)
                .toList();
        return new IndexDocsResult(total, buildDocs(spus));
    }

    @Override
    @Transactional(readOnly = true)
    public IndexDocsResult byBrand(long brandId) {
        long total = countOnShelf();
        // 与单体 syncByBrand 等价：只取该品牌**在架**的 spu
        List<Spu> spus = spuMapper.selectList(new LambdaQueryWrapper<Spu>()
                .eq(Spu::getBrandId, brandId)
                .eq(Spu::getStatus, EnableStatus.ENABLED));
        return new IndexDocsResult(total, buildDocs(spus));
    }

    @Override
    @Transactional(readOnly = true)
    public IndexDocsResult page(long pageNum, long pageSize) {
        long total = countOnShelf();
        long page = pageNum <= 0 ? 1L : pageNum;
        long size = pageSize <= 0 ? MAX_PAGE_SIZE : Math.min(MAX_PAGE_SIZE, pageSize);
        long offset = (page - 1) * size;
        if (offset >= total) {
            return new IndexDocsResult(total, List.of());
        }
        // ⚠️ 必须**按 id 升序**：调用方（reindex）逐页拉，顺序不稳定会导致漏文档/重复文档。
        //    与单体 reindex 的"一次取全部"相比，这里多了排序（单体没排序，因为它是整表取）。
        List<Spu> spus = spuMapper.selectList(new LambdaQueryWrapper<Spu>()
                .eq(Spu::getStatus, EnableStatus.ENABLED)
                .orderByAsc(Spu::getId)
                .last("LIMIT " + offset + "," + size));
        return new IndexDocsResult(total, buildDocs(spus));
    }

    // ==================== private ====================

    /** 在架 spu 总数（{@code status=1}；MP 的逻辑删除条件由 @TableLogic 自动带上） */
    private long countOnShelf() {
        Long n = spuMapper.selectCount(new LambdaQueryWrapper<Spu>()
                .eq(Spu::getStatus, EnableStatus.ENABLED));
        return n == null ? 0L : n;
    }

    /** SPU 列表 → 索引文档列表（拼装口径与单体 buildDoc 逐字一致） */
    private List<ProductSearchDoc> buildDocs(List<Spu> spus) {
        if (spus == null || spus.isEmpty()) {
            return List.of();
        }
        // 聚合：整表一次查（口径 = deleted=0 AND status=1 的 MIN(price)/SUM(stock)），再按 spuId 取用
        Map<Long, SkuAggregate> aggregateBySpu = skuMapper.selectShelfAggregates().stream()
                .collect(Collectors.toMap(SkuAggregate::getSpuId, a -> a, (a, b) -> a));
        // 品牌名：一次全量取（与单体 reindex 一致，避免 N+1）
        Map<Long, String> brandNames = brandMapper.selectList(null).stream()
                .collect(Collectors.toMap(Brand::getId, Brand::getName, (a, b) -> a));

        return spus.stream()
                .map(spu -> buildDoc(spu, aggregateBySpu.get(spu.getId()),
                        spu.getBrandId() == null ? null : brandNames.get(spu.getBrandId())))
                .toList();
    }

    /** SPU + SKU 聚合 + 品牌名 → 索引文档（**与单体 {@code ProductSearchServiceImpl#buildDoc} 逐字一致**） */
    private ProductSearchDoc buildDoc(Spu spu, SkuAggregate agg, String brandName) {
        ProductSearchDoc doc = new ProductSearchDoc();
        doc.setSpuId(spu.getId());
        doc.setTitle(spu.getTitle());
        doc.setSubtitle(spu.getSubtitle());
        doc.setBrandId(spu.getBrandId());
        doc.setBrandName(brandName);
        doc.setCategoryId(spu.getCategoryId());
        doc.setMinPrice(agg == null || agg.getMinPrice() == null ? 0L : agg.getMinPrice());
        doc.setTotalStock(agg == null || agg.getTotalStock() == null ? 0 : agg.getTotalStock().intValue());
        doc.setSales(spu.getSales() == null ? 0 : spu.getSales());
        doc.setStatus(spu.getStatus());
        doc.setMainImage(spu.getMainImage());
        doc.setCreateTimeMillis(spu.getCreateTime() == null ? null
                : spu.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        return doc;
    }
}
