package com.mall.demo.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SKU 的<b>只读契约快照</b>（域间契约）。
 *
 * <p>为什么需要它：购物车、收藏、足迹都需要"商品的当前价格/图片/库存/上下架状态"，
 * 此前直接读 {@code pms_sku} 并把 {@code Sku} 实体拿过来用——于是商品表的结构
 * 成了别的域的编译依赖（架构闸门 B1/B2）。改用快照后，消费者只认识这份契约。
 *
 * <p>字段是"展示与校验所需的最小集合"：不含 {@code skuCode}、{@code sales}、审计字段等。
 * {@code specValues} 保持为<b>原始 JSON 字符串</b>（与表列一致），
 * 由展示方自己决定怎么渲染（例如 {@code JsonKit.toSpecText}）——契约不搬运展示逻辑。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkuSnapshotVO {

    private Long id;

    /** 所属 SPU */
    private Long spuId;

    /** 售价（分） */
    private Long price;

    /** 划线价（分） */
    private Long originalPrice;

    private String image;

    /** 规格值原始 JSON，如 {@code [{"name":"颜色","value":"黑色"}]} */
    private String specValues;

    /** 可售库存 */
    private Integer stock;

    /** 0停用 1启用，见 {@code common.constant.EnableStatus} */
    private Integer status;
}
