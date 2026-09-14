package com.mall.search.dto;

import lombok.Data;

/**
 * Elasticsearch 商品检索文档(索引 {@code mall_product}，文档 _id = spuId)。
 *
 * <p>设计口径：
 * <ul>
 *   <li>只索引**上架**商品(SPU status=1)，下架/删除即不在索引里，避免前台搜索漏出</li>
 *   <li>字段覆盖"检索 + 筛选 + 排序"所需：标题/副标题(检索)、品牌/类目(筛选)、
 *       最低价/销量/创建时间(排序)；展示字段只带 mainImage(不参与检索)</li>
 *   <li>不存 LocalDateTime，统一存 {@code createTimeMillis} 毫秒时间戳 ——
 *       规避 JSON 日期序列化格式与 ES date 映射不一致的问题</li>
 *   <li>真正返回给前端的 VO 仍复用 MySQL 侧装配(ProductListAssembler)，
 *       ES 只负责"找出哪些 spuId 命中 + 排序"，因此不存在两份展示口径</li>
 * </ul>
 */
@Data
public class ProductSearchDoc {

    private Long spuId;
    /** 商品标题(检索字段，当前用默认 standard 分词；中文分词插件后续单独升级) */
    private String title;
    private String subtitle;
    private Long brandId;
    private String brandName;
    private Long categoryId;
    /** 在架 SKU 最低价(分) */
    private Long minPrice;
    private Integer sales;
    /** 在架 SKU 总库存 */
    private Integer totalStock;
    private Integer status;
    private String mainImage;
    /** 创建时间(毫秒时间戳)，用于 newest 排序 */
    private Long createTimeMillis;
}
