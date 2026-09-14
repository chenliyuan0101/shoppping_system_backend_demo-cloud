package com.mall.demo.admin.controller;

import com.mall.demo.admin.dto.EsPingVO;
import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.BusinessException;
import com.mall.demo.common.client.SearchOpsClient;
import com.mall.demo.common.dto.ReindexResult;
import com.mall.demo.common.dto.SearchStatusVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Elasticsearch 管理与自检接口。
 *
 * <p>路径在 /api/admin/** 下 → 自动要求管理员登录（AdminAuthInterceptor）。
 * <p>自检接口在 ES 不可用时**不抛异常**，返回 available=false + 错误摘要，便于运维一眼看出问题。
 *
 * <h2>P6-5 #6：本类**只剩转发**，单体不再持有 ES 客户端</h2>
 * 此前 {@code ping()} 用的是单体自己的 {@code ElasticsearchClient}（集群信息）+ 本地
 * {@code ProductSearchService}（文档数/分词器/待同步数）。索引域早就搬到 {@code mall-search}
 * （P6-2/P6-4），单体留着这套客户端的唯一后果是：**依赖、配置、证书、连接池全都还在**，
 * 而它们已经没有任何真正的用途 —— P6-6 也正因此删不掉 {@code com.mall.demo.pms} 整包。
 * 现在两个端点都转发到 {@code mall-search}（{@code /status} 与 {@code /reindex}）：
 * <ul>
 *   <li>对外契约（路径、字段名、{@code @JsonInclude(NON_NULL)} 的"可用/不可用"两条分支）<b>一字未改</b>；</li>
 *   <li>五个集群字段（clusterName/nodeName/esVersion/status/numberOfNodes）现在由检索域提供
 *       —— 见 {@code SearchStatusVO} 与 {@code mall-search} 的 {@code SearchStatusVO}；</li>
 *   <li>四个索引观测字段（productIndex/productDocCount/productTitleAnalyzer/productPendingSync）
 *       同样来自检索域（本来就是它的数据）。</li>
 * </ul>
 *
 * <h2>两条"不可用"必须分清（本类的 available=false 有两个来源）</h2>
 * <ol>
 *   <li><b>检索域不可达</b>：转发本身失败（连不上/超时/非 0 码）⇒ {@code error} 写转发失败原因，
 *       四个观测字段按"读不到"取值（docCount=-1、analyzer=standard、pendingSync=-1）；</li>
 *   <li><b>检索域活着但它连不上 ES</b>：快照回来了，但集群字段是 null/-1 ⇒ {@code available=false} +
 *       {@code error} 说明"ES 不可用（检索域报告）"，四个观测字段用检索域给的值
 *       （docCount 通常就是 -1、pendingSync 仍是真实队列长度 —— 与改造前"Redis 可读"的行为一致）。</li>
 * </ol>
 * 两种情况都**不抛**：自检接口的语义是"如实报告现状"，抛 500 会让运维看不到原因。
 */
@Slf4j
@Tag(name = "后台-Elasticsearch")
@RestController
@RequestMapping("/api/admin/es")
@RequiredArgsConstructor
public class AdminEsController {

    /** P6-4（D5）+ P6-5 #6：重建索引与自检都转发到 {@code mall-search}，单体不再有 ES 客户端 */
    private final SearchOpsClient searchOpsClient;

    /** 分词器探测失败时的兜底值（与改造前单体本地实现、以及检索域的口径一致） */
    private static final String ANALYZER_FALLBACK = "standard";

    /**
     * 索引名：**只在"检索域不可达"这一条分支上用**（那种情况下检索域给不了值）。
     *
     * <p>为什么要写死一个常量而不是干脆不输出：改造前 {@code data.setProductIndex(...)} 是
     * <b>无条件</b>执行的（那时索引名来自本地实现的常量）⇒ 不可用分支里**也有**这个字段。
     * 这是 C1 的形状（键集合）问题，不是"多一个少一个无所谓"：本项目已经因为
     * "看起来一样的响应其实少一个键"踩过坑，所以宁可留一个常量也不改形状。
     * 检索域可达时用它的 {@code index} 字段（同值），不再依赖这个常量。
     */
    private static final String PRODUCT_INDEX_FALLBACK = "mall_product";

    @Operation(summary = "ES 连通性自检(返回集群名/版本/健康状态/商品索引文档数)")
    @GetMapping("/ping")
    public ApiResponse<EsPingVO> ping() {
        EsPingVO data = new EsPingVO();

        SearchStatusVO status;
        try {
            status = searchOpsClient.status();
        } catch (BusinessException e) {
            // 来源 ①：检索域不可达 —— 与"ES 不可用"同形（available=false + error），且不抛
            log.warn("Elasticsearch 自检失败（检索域不可达）: {}", e.getMessage());
            data.setAvailable(false);
            data.setError(e.getMessage());
            data.setProductIndex(PRODUCT_INDEX_FALLBACK);   // ⚠️ 形状：不可用分支也要有它（C1）
            data.setProductDocCount(-1L);
            data.setProductTitleAnalyzer(ANALYZER_FALLBACK);
            data.setProductPendingSync(-1L);
            return ApiResponse.ok(data);
        }

        // 商品索引观测：文档数(-1 = 索引还不存在/ES 不可用)/标题分词器/待同步队列长度
        data.setProductIndex(status.index());
        data.setProductDocCount(status.docCount());
        data.setProductTitleAnalyzer(status.titleAnalyzer());
        data.setProductPendingSync(status.pendingCount());

        if (status.clusterName() == null || status.healthStatus() == null) {
            // 来源 ②：检索域活着，但它报告 ES 不可用（快照里集群字段为空）
            log.warn("Elasticsearch 自检失败（检索域报告 ES 不可用）: docCount={}", status.docCount());
            data.setAvailable(false);
            data.setError("Elasticsearch 不可用（由检索域报告：集群信息为空）");
            return ApiResponse.ok(data);
        }

        data.setAvailable(true);
        data.setClusterName(status.clusterName());
        data.setNodeName(status.nodeName());
        data.setEsVersion(status.esVersion());
        data.setStatus(status.healthStatus());          // green / yellow / red
        data.setNumberOfNodes(status.numberOfNodes());
        return ApiResponse.ok(data);
    }

    @Operation(summary = "全量重建商品索引(在架商品；幂等，可重复执行)")
    @PostMapping("/product/reindex")
    public ApiResponse<ReindexResult> reindex() {
        // ⚠️ P6-4（D5）：**绝不能**再调本地的 productSearchService.reindex() —— 那份实现读的是
        //    mall 库（冻结副本），切流量后会把旧文档重新写回索引（§四点六 实测到的事故）。
        //    转发给 mall-search：取数走 product 契约（真值），写入由 search 负责。
        return ApiResponse.ok(searchOpsClient.reindex());
    }
}
