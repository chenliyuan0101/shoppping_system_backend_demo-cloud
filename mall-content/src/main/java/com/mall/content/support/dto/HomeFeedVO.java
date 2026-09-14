package com.mall.content.support.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 首页商品区块：内容域在做首页聚合时，从商品域<b>一次</b>取走的全部商品数据。
 *
 * <p>为什么是"一个组合接口"而不是三个（类目树 / 热门 / 新品）：
 * 首页是最高频入口，且拆分后这是**跨进程调用**——三次往返意味着三个超时点、
 * 三种"部分失败"的组合。合成一次后降级语义只有一种：**这次调用失败 → 三个区块一起为空**
 * （见《微服务改造方案.md》§2.9）。
 *
 * <p>字段与 {@code HomeData} 中的同名区块逐字对应（C1：响应字段不变），
 * 本快照用于 P0 契约面 → 将来 {@code mall-content} 的 adapter 直接把它序列化成
 * {@code GET /internal/v1/product/home-feed} 的 {@code data}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeFeedVO {

    /** 启用类目树（顶级含 children） */
    private List<CategoryNode> categories;

    /** 热门商品：默认排序（销量倒序）的首页条数 */
    private List<ProductListItemVO> hotProducts;

    /** 新品：最新上架的首页条数 */
    private List<ProductListItemVO> newProducts;
}
