package com.mall.product.support.dto;

/**
 * 商品索引重建结果(后台全量重建接口返回)。
 *
 * @param index         索引名
 * @param titleAnalyzer 标题分词器(实测生效值：smartcn / cjk / standard)
 * @param indexed       本次写入的文档数
 * @param onShelfTotal  参与重建的在架 SPU 数(= 期望文档数，与 indexed 不等说明有写入失败)
 * @param tookMillis    耗时(毫秒)
 */
public record ReindexResult(String index, String titleAnalyzer, long indexed, long onShelfTotal, long tookMillis) {
}
