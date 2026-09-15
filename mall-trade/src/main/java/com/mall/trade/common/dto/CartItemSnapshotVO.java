package com.mall.trade.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 购物车条目快照（域间契约）：结算时读取"要买什么、买几件"。
 *
 * <p>刻意只带三个字段：条目 id、SKU id、数量。
 * 结算链路不需要购物车展示用的那些字段（价格/图片/规格文案/失效标记）——
 * 那些是 {@code CartItemVO} 的职责，而且价格与库存必须在下单时<b>重新</b>从商品域取，
 * 拿购物车里的旧值会下错单。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartItemSnapshotVO {

    /** 购物车条目 id */
    private Long itemId;

    private Long skuId;

    private Integer quantity;
}
