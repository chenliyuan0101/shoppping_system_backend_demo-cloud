package com.mall.usercenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.usercenter.client.ProductSnapshotClient;
import com.mall.usercenter.support.BusinessException;
import com.mall.usercenter.support.PageKit;
import com.mall.usercenter.support.PageResult;
import com.mall.usercenter.support.constant.EnableStatus;
import com.mall.usercenter.support.dto.SpuSnapshotVO;
import com.mall.usercenter.domain.Favorite;
import com.mall.usercenter.domain.Footprint;
import com.mall.usercenter.dto.FavoriteVO;
import com.mall.usercenter.dto.FootprintVO;
import com.mall.usercenter.mapper.FavoriteMapper;
import com.mall.usercenter.mapper.FootprintMapper;
import com.mall.usercenter.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 收藏/足迹实现：收藏支持开关(unique member+spu)；足迹按 member+spu 唯一、重复浏览刷新时间。
 */
@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final FootprintMapper footprintMapper;
    /**
     * 商品域出站客户端（P3-3：搬迁前是同进程的 {@code pms.service.ProductQueryService}）：
     * 收藏/足迹列表的标题/主图/销量/上下架都按需远程取，商品表不在本服务的库里。
     */
    private final ProductSnapshotClient productSnapshotClient;

    @Override
    @Transactional
    public boolean toggleFavorite(Long memberId, Long spuId) {
        SpuSnapshotVO spu = productSnapshotClient.spu(spuId);
        if (spu == null || spu.getStatus() == null || spu.getStatus() != EnableStatus.ENABLED) {
            throw new BusinessException(404, "商品不存在或已下架");
        }
        Favorite existing = favoriteMapper.selectOne(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getMemberId, memberId).eq(Favorite::getSpuId, spuId));
        if (existing != null) {
            favoriteMapper.deleteById(existing.getId());
            return false;
        }
        Favorite favorite = new Favorite();
        favorite.setMemberId(memberId);
        favorite.setSpuId(spuId);
        favoriteMapper.insert(favorite);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<FavoriteVO> favoritePage(Long memberId, long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, 50);
        LambdaQueryWrapper<Favorite> base = new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getMemberId, memberId)
                .orderByDesc(Favorite::getCreateTime);
        long total = favoriteMapper.selectCount(base);
        List<Favorite> list = favoriteMapper.selectList(base.last("LIMIT " + ((page - 1) * size) + "," + size));
        List<Long> spuIds = list.stream().map(Favorite::getSpuId).toList();
        Map<Long, SpuSnapshotVO> spuMap = spuMap(spuIds);
        Map<Long, Long> priceMap = minPrices(spuIds);
        List<FavoriteVO> vos = list.stream().map(f -> {
            SpuSnapshotVO spu = spuMap.get(f.getSpuId());
            FavoriteVO vo = new FavoriteVO();
            vo.setSpuId(f.getSpuId());
            vo.setTitle(spu == null ? null : spu.getTitle());
            vo.setMainImage(spu == null ? null : spu.getMainImage());
            vo.setPrice(priceMap.getOrDefault(f.getSpuId(), 0L));
            vo.setSales(spu == null ? 0 : spu.getSales());
            vo.setCreateTime(f.getCreateTime());
            return vo;
        }).toList();
        return PageResult.of(total, page, size, vos);
    }

    @Override
    @Transactional
    public void recordFootprint(Long memberId, Long spuId) {
        SpuSnapshotVO spu = productSnapshotClient.spu(spuId);
        if (spu == null || spu.getStatus() == null || spu.getStatus() != EnableStatus.ENABLED) {
            return;   // 下架/不存在不记录
        }
        Footprint existing = footprintMapper.selectOne(new LambdaQueryWrapper<Footprint>()
                .eq(Footprint::getMemberId, memberId).eq(Footprint::getSpuId, spuId));
        if (existing != null) {
            existing.setLastViewTime(LocalDateTime.now());
            footprintMapper.updateById(existing);
        } else {
            Footprint footprint = new Footprint();
            footprint.setMemberId(memberId);
            footprint.setSpuId(spuId);
            footprint.setLastViewTime(LocalDateTime.now());
            footprintMapper.insert(footprint);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<FootprintVO> footprintPage(Long memberId, long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, 50);
        LambdaQueryWrapper<Footprint> base = new LambdaQueryWrapper<Footprint>()
                .eq(Footprint::getMemberId, memberId)
                .orderByDesc(Footprint::getLastViewTime);
        long total = footprintMapper.selectCount(base);
        List<Footprint> list = footprintMapper.selectList(base.last("LIMIT " + ((page - 1) * size) + "," + size));
        List<Long> spuIds = list.stream().map(Footprint::getSpuId).toList();
        Map<Long, SpuSnapshotVO> spuMap = spuMap(spuIds);
        Map<Long, Long> priceMap = minPrices(spuIds);
        List<FootprintVO> vos = list.stream().map(f -> {
            SpuSnapshotVO spu = spuMap.get(f.getSpuId());
            FootprintVO vo = new FootprintVO();
            vo.setSpuId(f.getSpuId());
            vo.setTitle(spu == null ? null : spu.getTitle());
            vo.setMainImage(spu == null ? null : spu.getMainImage());
            vo.setPrice(priceMap.getOrDefault(f.getSpuId(), 0L));
            vo.setLastViewTime(f.getLastViewTime());
            return vo;
        }).toList();
        return PageResult.of(total, page, size, vos);
    }

    @Override
    public void clearFootprint(Long memberId) {
        footprintMapper.delete(new LambdaQueryWrapper<Footprint>()
                .eq(Footprint::getMemberId, memberId));
    }

    // ---------- private ----------

    private Map<Long, SpuSnapshotVO> spuMap(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return productSnapshotClient.spus(distinct).stream()
                .collect(Collectors.toMap(SpuSnapshotVO::getId, s -> s));
    }

    /**
     * 批量取"启用 SKU 最低价"：改造前是逐行单查（N+1），改为一次批量（语义完全一致）。
     *
     * <p>P3-3 起该值来自商品域的 {@code POST /internal/v1/product/sku/min-price/batch}；
     * 下游不可用时客户端降级为空 Map（调用方口径不变：缺失即起售价 0）——
     * 详见 {@link ProductSnapshotClient#minEnabledSkuPrices}。
     */
    private Map<Long, Long> minPrices(List<Long> spuIds) {
        List<Long> distinct = spuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return productSnapshotClient.minEnabledSkuPrices(distinct);
    }
}
