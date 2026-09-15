package com.mall.trade.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * ES 连通性自检结果(字段名与原 Map 键一一对应)。
 *
 * <p>ES 不可用时只返回 available + error(没有集群信息)，用 NON_NULL 保证
 * "可用 / 不可用"两条分支的 JSON 与改造前完全一致。
 */
@Data
@Schema(description = "Elasticsearch 自检结果")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EsPingVO {

    /** ES 是否可用 */
    private Boolean available;

    /** 集群名(不可用时为 null) */
    private String clusterName;

    /** 节点名 */
    private String nodeName;

    /** ES 版本 */
    private String esVersion;

    /** 健康状态 green / yellow / red */
    private String status;

    /** 节点数 */
    private Integer numberOfNodes;

    /** 不可用时的错误摘要(可用时为 null) */
    private String error;

    /** 商品索引名 */
    private String productIndex;

    /** 商品索引文档数(-1 = 索引还不存在) */
    private Long productDocCount;

    /** 标题分词器 */
    private String productTitleAnalyzer;

    /** 待同步队列长度 */
    private Long productPendingSync;
}
