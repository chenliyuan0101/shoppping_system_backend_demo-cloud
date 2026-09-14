package com.mall.admin.support.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SPU 只读快照：单体 {@code com.mall.demo.common.dto.SpuSnapshotVO} 的契约快照
 * （{@code POST /internal/v1/product/spu/batch} 与 {@code GET /internal/v1/product/stat/top-sales} 的元素形状）。
 *
 * <p>看板用它两处：销量榜每个 SPU 的 {@code id/title/mainImage/sales}；
 * 销售额榜按 {@code spuId} 补 {@code title/mainImage}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpuSnapshotVO {

    private Long id;

    private String title;

    private String subtitle;

    private String mainImage;

    /** 展示销量 */
    private Integer sales;

    /** 0下架 1上架 */
    private Integer status;
}
