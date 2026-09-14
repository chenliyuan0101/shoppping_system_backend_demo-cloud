package com.mall.product.service;

import com.mall.product.dto.IndexDocsResult;

import java.util.Collection;

/**
 * 索引文档供给契约（**P6-2 新增**，供 {@code mall-search} 取"写 ES 文档所需的字段"）。
 *
 * <h2>为什么会有这个契约（P6-2 规格 §3 的核心）</h2>
 * 拆分后 {@code mall-search} **没有 MySQL**，而索引文档的字段口径
 * （"在架 SKU 的最低价 / 总库存聚合、品牌名、{@code createTimeMillis}"）是**商品域的知识**。
 * 于是把"取内容"从"search 直接读表"改成"search 向 product 拉"，分三个取法覆盖三种调用场景：
 * <pre>
 *  取法                谁在用                                拆分前的对应实现
 *  bySpuIds(spuIds)   订单/后台单条同步（MQ 消费、syncLater）  pms.ProductSearchServiceImpl#syncProduct
 *  byBrand(brandId)   品牌改名/删除后批量重写                  #syncByBrand
 *  page(pageNum,size) 全量重建（逐页拉）                        #reindex 的"取数"三步聚合
 * </pre>
 *
 * <p>⚠️ **口径必须与拆分前逐字一致**（否则 P6-4 切换后检索结果会漂移）：
 * 最低价/总库存只统计 {@code status=1} 的 SKU、忽略价格为空的；品牌名取 {@code pms_brand.name}；
 * {@code createTimeMillis} 用 {@code ZoneId.systemDefault()} 换算；下架或逻辑删除的商品**不产生文档**
 * （由调用方按差集删除索引文档）。
 */
public interface ProductIndexDocService {

    /** 按 spuId 取索引文档：**只返回在架且未删除**的（调用方按差集删除索引里多出来的那些） */
    IndexDocsResult bySpuIds(Collection<Long> spuIds);

    /** 按品牌取"该品牌下在架商品"的索引文档 */
    IndexDocsResult byBrand(long brandId);

    /** 分页取全量在架商品的索引文档（{@code spuId} 升序，保证翻页稳定）；{@code pageNum} 从 1 开始 */
    IndexDocsResult page(long pageNum, long pageSize);
}
