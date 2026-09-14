package com.mall.usercenter.support.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SPU 的<b>只读契约快照</b>（域间契约）。
 *
 * <p>与 {@link SkuSnapshotVO} 同理：购物车/收藏/足迹展示商品标题与主图、判断上下架，
 * 需要的是这几个字段，而不是整张 {@code pms_spu} 表。
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

    /** 0下架 1上架，见 {@code common.constant.EnableStatus} */
    private Integer status;
}
