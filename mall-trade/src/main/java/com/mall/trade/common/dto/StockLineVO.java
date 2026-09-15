package com.mall.trade.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 库存/销量变更的一行（域间契约）：{@code skuId + spuId + quantity}。
 *
 * <p>为什么带 {@code spuId}：库存与销量是两个粒度的展示数据——扣减/回补作用在 SKU，
 * 而"销量"要同时累加到 SKU 与 SPU。带上 spuId 后，商品域不必为了累加 SPU 销量再反查一次 SKU，
 * 也让"这次操作动了哪些 SPU（要标记检索索引待同步）"对所有方法都可用。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code skuId}：必填，库存/销量的作用对象；</li>
 *   <li>{@code spuId}：所属 SPU。预占/回补时用于"标记索引待同步"，
 *       累加销量时用于更新 {@code pms_spu.sales}；为 null 时实现会按 SKU 反查；</li>
 *   <li>{@code quantity}：正整数，表示本次变更的<b>绝对数量</b>——
 *       扣减还是回补由调用的方法决定，不在这里用正负号表达。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockLineVO {

    private Long skuId;

    private Long spuId;

    /** 数量（正数）：扣减/回补/销量的绝对值 */
    private Integer quantity;
}
