package com.mall.usercenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.usercenter.client.ProductSnapshotClient;
import com.mall.usercenter.support.BusinessException;
import com.mall.common.support.JsonKit;
import com.mall.usercenter.support.constant.EnableStatus;
import com.mall.common.constant.YesNo;
import com.mall.usercenter.support.dto.SkuSnapshotVO;
import com.mall.usercenter.support.dto.SpuSnapshotVO;
import com.mall.usercenter.domain.CartItem;
import com.mall.usercenter.dto.CartItemVO;
import com.mall.usercenter.mapper.CartItemMapper;
import com.mall.usercenter.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import com.mall.common.support.MemberId;

/**
 * 购物车实现：条目存 ums_cart_item，展示时关联 SKU/SPU 现价与规格。
 */
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private static final int MAX_PER_LINE = 99;

    private final CartItemMapper cartItemMapper;
    /**
     * 商品域出站客户端（P3-3：搬迁前是同进程的 {@code pms.service.ProductQueryService}）。
     *
     * <p>商品表（{@code pms_sku}/{@code pms_spu}）不在本服务的库里，因此"现价/库存/上下架/规格"
     * 只能按需远程取——这也正是购物车搬过来之后，**库存校验口径仍在商品域**的保证。
     */
    private final ProductSnapshotClient productSnapshotClient;

    @Override
    @Transactional(readOnly = true)
    public List<CartItemVO> list(Long memberId) {
        List<CartItem> rows = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getMemberId, memberId)
                .orderByDesc(CartItem::getUpdateTime).orderByDesc(CartItem::getId));

        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> skuIds = rows.stream().map(CartItem::getSkuId).distinct().toList();
        List<Long> spuIds = rows.stream().map(CartItem::getSpuId).filter(Objects::nonNull).distinct().toList();
        Map<Long, SkuSnapshotVO> skuMap = productSnapshotClient.skus(skuIds).stream()
                .collect(Collectors.toMap(SkuSnapshotVO::getId, s -> s));
        Map<Long, SpuSnapshotVO> spuMap = productSnapshotClient.spus(spuIds).stream()
                .collect(Collectors.toMap(SpuSnapshotVO::getId, s -> s));

        List<CartItemVO> vos = new ArrayList<>();
        for (CartItem row : rows) {
            SkuSnapshotVO sku = skuMap.get(row.getSkuId());
            SpuSnapshotVO spu = spuMap.get(row.getSpuId());
            boolean invalid = sku == null || spu == null
                    || !Integer.valueOf(EnableStatus.ENABLED).equals(spu.getStatus())
                    || !Integer.valueOf(EnableStatus.ENABLED).equals(sku.getStatus());

            CartItemVO vo = new CartItemVO();
            vo.setItemId(row.getId());
            vo.setSkuId(row.getSkuId());
            vo.setSpuId(row.getSpuId());
            vo.setQuantity(row.getQuantity());
            vo.setChecked(Integer.valueOf(YesNo.YES).equals(row.getChecked()));
            vo.setInvalid(invalid);
            if (sku != null) {
                vo.setPrice(sku.getPrice());
                vo.setImage(sku.getImage() != null ? sku.getImage() : (spu != null ? spu.getMainImage() : null));
                vo.setStock(sku.getStock());
                vo.setSkuName(JsonKit.toSpecText(sku.getSpecValues()));
                vo.setTitle(spu != null ? spu.getTitle() : null);
            }
            vos.add(vo);
        }
        return vos;
    }


    @Override
    @Transactional
    public void add(Long memberId, Long skuId, Integer quantity) {
        if (skuId == null) {
            throw new BusinessException(400, "请选择商品规格");
        }
        int qty = quantity == null ? 1 : quantity;
        if (qty < 1) {
            throw new BusinessException(400, "数量至少为 1");
        }
        SkuSnapshotVO sku = productSnapshotClient.sku(skuId);
        if (sku == null || !Integer.valueOf(EnableStatus.ENABLED).equals(sku.getStatus())) {
            throw new BusinessException(400, "商品不存在或已下架");
        }
        SpuSnapshotVO spu = productSnapshotClient.spu(sku.getSpuId());
        if (spu == null || !Integer.valueOf(EnableStatus.ENABLED).equals(spu.getStatus())) {
            throw new BusinessException(400, "商品不存在或已下架");
        }
        CartItem existing = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getMemberId, memberId).eq(CartItem::getSkuId, skuId));
        int stock = sku.getStock() == null ? 0 : sku.getStock();
        int newQty = (existing == null ? 0 : existing.getQuantity()) + qty;
        if (newQty > Math.min(MAX_PER_LINE, stock)) {
            throw new BusinessException(409, "库存不足");
        }
        if (existing == null) {
            CartItem item = new CartItem();
            item.setMemberId(memberId);
            item.setSkuId(skuId);
            item.setSpuId(spu.getId());
            item.setQuantity(newQty);
            item.setChecked(YesNo.YES);
            cartItemMapper.insert(item);
        } else {
            existing.setQuantity(newQty);
            cartItemMapper.updateById(existing);
        }
    }

    @Override
    @Transactional
    public void updateQuantity(Long memberId, Long itemId, Integer quantity) {
        CartItem item = requireOwned(memberId, itemId);
        if (quantity == null || quantity < 1) {
            throw new BusinessException(400, "数量至少为 1");
        }
        SkuSnapshotVO sku = productSnapshotClient.sku(item.getSkuId());
        int stock = sku == null ? 0 : (sku.getStock() == null ? 0 : sku.getStock());
        if (quantity > Math.min(MAX_PER_LINE, stock)) {
            throw new BusinessException(409, "库存不足");
        }
        item.setQuantity(quantity);
        cartItemMapper.updateById(item);
    }

    @Override
    @Transactional
    public void updateChecked(Long memberId, Long itemId, boolean checked) {
        CartItem item = requireOwned(memberId, itemId);
        item.setChecked(checked ? YesNo.YES : YesNo.NO);
        cartItemMapper.updateById(item);
    }

    @Override
    @Transactional
    public void updateAllChecked(Long memberId, boolean checked) {
        CartItem update = new CartItem();
        update.setChecked(checked ? YesNo.YES : YesNo.NO);
        cartItemMapper.update(update, new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getMemberId, memberId));
    }

    @Override
    public void removeItem(Long memberId, Long itemId) {
        requireOwned(memberId, itemId);
        cartItemMapper.deleteById(itemId);
    }

    @Override
    public void removeChecked(Long memberId) {
        cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getMemberId, memberId).eq(CartItem::getChecked, YesNo.YES));
    }

    @Override
    @Transactional(readOnly = true)
    public int count(Long memberId) {
        List<CartItem> rows = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getMemberId, memberId));
        return rows.stream().mapToInt(r -> r.getQuantity() == null ? 0 : r.getQuantity()).sum();
    }

    private CartItem requireOwned(Long memberId, Long itemId) {
        CartItem item = cartItemMapper.selectById(itemId);
        if (item == null || !item.getMemberId().equals(memberId)) {
            throw new BusinessException(404, "购物车条目不存在");
        }
        return item;
    }
}
