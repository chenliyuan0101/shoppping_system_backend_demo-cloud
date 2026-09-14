package com.mall.search.internal;

import com.mall.search.dto.EsClusterInfo;
import com.mall.search.dto.MarkDirtyRequest;
import com.mall.search.dto.MarkDirtyResult;
import com.mall.search.dto.ProductIdPage;
import com.mall.search.dto.ProductSearchDoc;
import com.mall.search.dto.SearchProductsRequest;
import com.mall.search.dto.SearchStatusVO;
import com.mall.search.service.ProductSearchService;
import com.mall.search.support.ApiResponse;
import com.mall.search.support.dto.ReindexResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索域的内部接口（P6-2 新建；P6-3 起 product 的货架读路径会调它）。
 *
 * <p><b>八个端点</b>（规格 §4 的六个 + P6-5 规格 §一 第 5 行补的两个空洞）：
 * 检索 / 全量重建 / 自检 / 单条同步 / 单条删除 / 按品牌同步 / **标记待同步** / **按 id 取单文档**。
 *
 * <p><b>鉴权</b>：整个 {@code /internal/**} 由 {@code InternalApiAuthInterceptor} 校验
 * {@code X-Internal-Token}；失败时返回的是 **HTTP 200 + body 里的 {@code code=403}**
 * （{@code BusinessException} 经 {@code GlobalExceptionHandler} 转成统一响应体）——
 * 调用方必须按 {@code code} 判成败，不能只看 HTTP 状态码。
 *
 * <p>⚠️ <b>本服务不自己查 MySQL 做降级</b>（规格 §5 第 5 条）：ES 不可达时这里**如实报错**
 * （{@code search} 抛 {@code IllegalStateException} → 由 GlobalExceptionHandler 兜底成 500 业务码；
 * {@code status} 报 {@code docCount=-1}）。"ES 挂了 → 回落 MySQL LIKE" 这条降级**属于 product**
 * （它才有库），P6-3 落地；两边各写一半是最坏的结果。
 */
@RestController
@RequestMapping("/internal/v1/search")
@RequiredArgsConstructor
public class SearchInternalController {

    private final ProductSearchService productSearchService;

    /**
     * 按条件检索：返回命中的 spuId（已排序）+ 命中总数。
     *
     * <p>只返回 id 与总数——展示字段（标题/图/价）由调用方回自己的库装配，与拆分前的分工一致。
     */
    @PostMapping("/products")
    public ApiResponse<ProductIdPage> products(@RequestBody SearchProductsRequest request) {
        return ApiResponse.ok(productSearchService.search(
                request.keyword(), request.categoryIds(), request.brandId(),
                request.minPrice(), request.maxPrice(), request.sort(),
                request.normalizedPageNum(), request.normalizedPageSize()));
    }

    /**
     * 全量重建索引（幂等：可反复执行）。
     *
     * <p>⚠️ 失败时**明确报错**、绝不"写出一份半截索引却声称成功"（规格 §6）：
     * 取数阶段（向 product 拉文档）失败会直接抛出，此时**索引未被改动**。
     */
    @PostMapping("/reindex")
    public ApiResponse<ReindexResult> reindex() {
        return ApiResponse.ok(productSearchService.reindex());
    }

    /**
     * 自检快照：文档数 / 待同步数 / 分词器（不可用时按现状返回 -1，见 {@code SearchStatusVO}）
     * + **ES 集群信息**（clusterName/nodeName/esVersion/healthStatus/numberOfNodes，P6-5 #6）。
     *
     * <p>集群信息是给单体 {@code GET /api/admin/es/ping} 转发用的：转发落地后单体**不再持有 ES 客户端**
     * （P6-6 才能干净地删 {@code com.mall.demo.pms} 整包）。ES 不可达时它是
     * {@code null/null/null/null/-1}（**分支形状与 {@code EsPingVO} 一致**），本接口照常返回 200/code=0。
     */
    @GetMapping("/status")
    public ApiResponse<SearchStatusVO> status() {
        EsClusterInfo cluster = productSearchService.esClusterInfo();
        return ApiResponse.ok(new SearchStatusVO(
                ProductSearchService.INDEX,
                productSearchService.count(),
                productSearchService.pendingCount(),
                productSearchService.titleAnalyzer(),
                cluster.clusterName(),
                cluster.nodeName(),
                cluster.esVersion(),
                cluster.healthStatus(),
                cluster.numberOfNodes()));
    }

    /**
     * 单条同步（后台改完商品要"改完就能搜到"）。
     *
     * <p>返回 {@code true}=已按最新状态落索引（含"商品已下架 → 从索引删除"这种也算成功），
     * {@code false}=同步失败（ES 不可达/内容拉不到）——失败**不抛异常**，
     * 与现状一致（索引同步失败不该阻塞商品写操作，靠重试/全量重建兜底）。
     */
    @PostMapping("/sync/{spuId}")
    public ApiResponse<Boolean> sync(@PathVariable long spuId) {
        return ApiResponse.ok(productSearchService.syncProduct(spuId));
    }

    /** 从索引删除（幂等：文档不存在也算成功——ES delete 本身就是幂等的） */
    @DeleteMapping("/product/{spuId}")
    public ApiResponse<Void> delete(@PathVariable long spuId) {
        productSearchService.deleteProduct(spuId);
        return ApiResponse.ok();
    }

    /** 品牌改名/删除后按品牌批量重写；返回成功同步的条数（与现状 {@code syncByBrand} 同语义） */
    @PostMapping("/sync-by-brand/{brandId}")
    public ApiResponse<Integer> syncByBrand(@PathVariable long brandId) {
        return ApiResponse.ok(productSearchService.syncByBrand(brandId));
    }

    // ==================================================================
    // P6-5 #5：补上两个"空洞"端点（不是新功能——是让既有的意图有地方可去）
    // ==================================================================

    /**
     * <b>标记待同步</b>（P6-5 规格 §一 第 5 行 ①）：把 spuId 写进 Redis 待同步集合
     * （{@code mall:es:pending}），由本服务的 {@code ProductSearchSyncTask} 定时 drain。
     *
     * <p>在此之前，调用方只能自己去写那把共享的 Redis 键 —— "标记待同步"这个意图在 search 侧
     * **没有端点可落**（P6-4 的记账原文）。现在补上。
     *
     * <p>请求 {@code {"spuIds":[1,2,3]}}；响应 {@code data = {"added":N}}（本次新增条数，
     * Redis 不可用时 {@code -1}）。空/null 列表 ⇒ {@code added:0}，**不是**错误。
     *
     * <p>⚠️ 收敛延迟是定时任务的周期（默认 15s）：**亚秒级通道是 MQ**，其发布方按 D1 归 product
     * （见 {@code ProductSearchService#markPending} 的 javadoc）。
     */
    @PostMapping("/mark-dirty")
    public ApiResponse<MarkDirtyResult> markDirty(@RequestBody(required = false) MarkDirtyRequest request) {
        return ApiResponse.ok(new MarkDirtyResult(
                productSearchService.markPending(request == null ? null : request.spuIds())));
    }

    /**
     * <b>按 id 取单文档</b>（P6-5 规格 §一 第 5 行 ②）：返回索引里这篇商品的文档。
     *
     * <p>这是 {@code mall-product} 的 {@code RemoteProductSearchService.findById} 一直"如实返回 null"
     * 的那个空洞：以前没有端点可调，现在有了。响应 {@code data} = 文档（12 个字段的键值对），
     * 文档不存在 ⇒ {@code data:null}（**code 仍是 0**：这是结论，不是错误）。
     *
     * <p>⚠️ 与"不存在"不同，ES 不可达时本端点**明确报错**（code=500）：把"读不到"报成"不存在"
     * 会让调用方得出完全相反的结论（详见 {@code ProductSearchService#getIndexedDoc}）。
     *
     * <p>方法与既有 {@code DELETE /product/{spuId}} **同路径不同方法**（取 vs 删），
     * 路径不加后缀：product 侧要的正是"给我这个 spuId 的索引文档"。
     */
    @GetMapping("/product/{spuId}")
    public ApiResponse<ProductSearchDoc> product(@PathVariable long spuId) {
        return ApiResponse.ok(productSearchService.getIndexedDoc(spuId));
    }
}
