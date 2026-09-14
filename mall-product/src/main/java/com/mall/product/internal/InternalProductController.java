package com.mall.product.internal;

import com.mall.product.dto.IndexDocsResult;
import com.mall.product.service.ProductIndexDocService;
import com.mall.product.service.ProductQueryService;
import com.mall.product.service.StockCommandService;
import com.mall.product.support.ApiResponse;
import com.mall.product.support.BusinessException;
import com.mall.product.support.dto.HomeFeedVO;
import com.mall.product.support.dto.SkuSnapshotVO;
import com.mall.product.support.dto.SpuSnapshotVO;
import com.mall.product.support.dto.StockLineVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 商品域的内部接口：只读查询（{@code ProductQueryService}）与库存写（{@code StockCommandService}）。
 *
 * <p>**逐字搬运**：本类与单体的 {@code com.mall.demo.internal.InternalProductController}
 * 的 7 个映射**路径、请求体、响应体完全相同**（只换了包名）——从 P6-4 起 trade 会真的调它，
 * 请求/响应形状是跨服务的 C1 契约，不允许"顺手改好一点"。
 *
 * <p>⚠️ 这几个写端点将来是"跨库写"的入口（方案 §1.3 最严重的三处），因此：
 * 调用方必须按 {@code orderNo} 幂等、按 {@code skuId} 升序传入，
 * 且实现内部的"单条 SQL 防超卖 / 按 skuId 加锁 / 更新后再读写流水"细节不得因为跨了 HTTP 而简化
 * （5 条细节见 {@code StockCommandServiceImpl} 类注释，逐条有对应用例）。
 *
 * <p><b>鉴权</b>：整个 {@code /internal/**} 由 {@code InternalApiAuthInterceptor} 校验
 * {@code X-Internal-Token}。⚠️ 失败时返回的是 **HTTP 200 + body 里的 {@code code=403}**
 * （{@code BusinessException} 经 {@code GlobalExceptionHandler} 转成统一响应体）——
 * 调用方必须按 {@code code} 判成败，不能只看 HTTP 状态码。
 */
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalProductController {

    /** 通用 id 集合请求体 */
    public record IdsRequest(List<Long> ids) {
    }

    /** 库存变更请求体（预占/销量共用） */
    public record StockRequest(String orderNo, List<StockLineVO> lines) {
    }

    /** 库存回补请求体：多一个 changeType（决定流水口径） */
    public record StockReleaseRequest(String orderNo, int changeType, List<StockLineVO> lines) {
    }

    /**
     * 索引文档请求体（**P6-2 新增**）：三种取法**只能给一种**。
     *
     * <pre>
     *  {spuIds:[..]}              按 spuId 取（只返回在架且未删除的；调用方按差集删除索引文档）
     *  {brandId:N}                该品牌下在架商品（品牌改名/删除后批量重写）
     *  {pageNum:N, pageSize:N}    全量分页（重建用；spuId 升序，翻页稳定）
     * </pre>
     */
    public record IndexDocsRequest(List<Long> spuIds, Long brandId, Long pageNum, Long pageSize) {
    }

    private final ProductQueryService productQueryService;
    private final StockCommandService stockCommandService;
    /** P6-2 新增：向 mall-search 供给"索引文档内容"（索引文档怎么拼是商品域的知识，见契约类注释） */
    private final ProductIndexDocService productIndexDocService;
    /** P6-4 新增：商品看板统计（在架数 / 热度榜）——原来单体直读 {@code pms_spu}，术后走这里 */
    private final com.mall.product.service.ProductStatQueryService productStatQueryService;

    @PostMapping("/product/sku/batch")
    public ApiResponse<List<SkuSnapshotVO>> skus(@RequestBody IdsRequest request) {
        return ApiResponse.ok(productQueryService.skus(emptyIfNull(request.ids())));
    }

    /**
     * 批量取"每个 SPU 下启用 SKU 的最低价"（收藏/足迹列表展示"起售价"用）。
     *
     * <p>P3 补：user-center 搬走收藏/足迹后，这条读能力没有对应的内部端点，子代理只能猜路径
     * （猜的 404 → 降级成"起售价 0"）。缺端点看起来不影响功能，实际是**静默的展示退化**，
     * 正是拆分时最容易被忽略的一类回归。
     *
     * <p>口径与改造前一致（{@code ProductQueryService.minEnabledSkuPrices}）：只看 {@code status=1} 的 SKU、
     * 忽略价格为空的；没有可用 SKU 的 SPU **不会**出现在结果里（调用方按 0 处理）。
     * 返回 JSON 对象的 key 是 spuId（字符串形式，Jackson 惯例）。
     */
    @PostMapping("/product/sku/min-price/batch")
    public ApiResponse<Map<Long, Long>> minEnabledSkuPrices(@RequestBody IdsRequest request) {
        return ApiResponse.ok(productQueryService.minEnabledSkuPrices(emptyIfNull(request.ids())));
    }

    @PostMapping("/product/spu/batch")
    public ApiResponse<List<SpuSnapshotVO>> spus(@RequestBody IdsRequest request) {
        return ApiResponse.ok(productQueryService.spus(emptyIfNull(request.ids())));
    }

    /**
     * 首页商品区块（类目树 + 热门 + 新品）。
     *
     * <p>调用方是 {@code mall-content} 的首页聚合（方案 §2.9）：它必须**一次**拿到三块，
     * 失败即整块降级为空数组、首页只出 Banner/公告，绝不 5xx。
     * 条数由内容域传（默认 8），上限由商品域夹取。
     */
    @GetMapping("/product/home-feed")
    public ApiResponse<HomeFeedVO> homeFeed(@RequestParam(defaultValue = "8") int size) {
        return ApiResponse.ok(productQueryService.homeFeed(size));
    }

    @PostMapping("/stock/reserve")
    public ApiResponse<Void> reserve(@RequestBody StockRequest request) {
        stockCommandService.reserve(request.orderNo(), emptyIfNull(request.lines()));
        return ApiResponse.ok();
    }

    @PostMapping("/stock/release")
    public ApiResponse<Void> release(@RequestBody StockReleaseRequest request) {
        stockCommandService.release(request.orderNo(), emptyIfNull(request.lines()), request.changeType());
        return ApiResponse.ok();
    }

    @PostMapping("/stock/sales/increment")
    public ApiResponse<Void> incrementSales(@RequestBody StockRequest request) {
        stockCommandService.incrementSales(request.orderNo(), emptyIfNull(request.lines()));
        return ApiResponse.ok();
    }

    /**
     * <b>索引文档供给</b>（**P6-2 新增**，给 {@code mall-search} 用）。
     *
     * <p>为什么需要它：拆分后 {@code mall-search} **没有 MySQL**，而"索引文档怎么拼"
     * （在架 SKU 的最低价/总库存聚合、品牌名、{@code createTimeMillis}）是商品域的知识，
     * 所以检索域只能向商品域拉内容——这就是规格 §3 的"内容改为 search 向 product 拉"。
     *
     * <p>三种取法（**只能给一种**，给多了直接 400，不猜优先级）：
     * <ul>
     *   <li>{@code spuIds}：只返回**在架且未删除**的文档；调用方用"请求的 id − 返回的 id"算出要删的 id
     *       （等价于拆分前 {@code syncProduct} 里 {@code spu==null || status!=1 → 删除}）；</li>
     *   <li>{@code brandId}：该品牌下在架商品（{@code syncByBrand} 用）；</li>
     *   <li>{@code pageNum+pageSize}：全量分页（{@code reindex} 用），按 {@code spuId} 升序保证翻页稳定。</li>
     * </ul>
     * 字段口径与拆分前 {@code ProductSearchServiceImpl#buildDoc} **逐字一致**（含"无可用 SKU 时 minPrice=0"）。
     */
    @PostMapping("/product/index-docs")
    public ApiResponse<IndexDocsResult> indexDocs(@RequestBody IndexDocsRequest request) {
        boolean bySpuIds = request.spuIds() != null && !request.spuIds().isEmpty();
        boolean byBrand = request.brandId() != null;
        boolean byPage = request.pageNum() != null || request.pageSize() != null;
        int given = (bySpuIds ? 1 : 0) + (byBrand ? 1 : 0) + (byPage ? 1 : 0);
        if (given != 1) {
            throw new BusinessException(400,
                    "index-docs 的三种取法只能给一种：spuIds / brandId / pageNum+pageSize");
        }
        if (bySpuIds) {
            return ApiResponse.ok(productIndexDocService.bySpuIds(request.spuIds()));
        }
        if (byBrand) {
            return ApiResponse.ok(productIndexDocService.byBrand(request.brandId()));
        }
        long pageNum = request.pageNum() == null ? 1L : request.pageNum();
        long pageSize = request.pageSize() == null ? 500L : request.pageSize();
        return ApiResponse.ok(productIndexDocService.page(pageNum, pageSize));
    }

    // ==================================================================
    // P6-4 新增：商品看板的两个**读**能力（单体侧原本直读 pms_spu，术后改走这里）
    // ==================================================================

    /**
     * 在架（{@code status=1} 且未删除）商品数。
     *
     * <p>语义**逐字照抄**单体 {@code ProductStatQueryServiceImpl#countEnabled}
     * （那边现在只有这一份实现搬到了本服务）：{@code status=1} 计数，逻辑删除条件由 MP 的
     * {@code @TableLogic} 自动带上 ⇒ 返回 0 而不是 null。
     *
     * <p>调用方是单体的商品看板（P6-4 之后经远程契约读），**不是** search —— 索引文档数由
     * {@code /internal/v1/search/status} 报（两者口径不同：这里是库，那里是索引）。
     */
    @GetMapping("/product/stat/enabled-count")
    public ApiResponse<Long> enabledCount() {
        return ApiResponse.ok(productStatQueryService.countEnabled());
    }

    /**
     * 在架商品按销量倒序取前 N（热度榜）。
     *
     * <p>语义**逐字照抄**单体 {@code ProductStatQueryServiceImpl#topBySales}：
     * 排序与取前 N **都在 DB 完成**（不捞全表到内存排），{@code limit} 用同一个
     * {@code PageKit.size(limit, MAX_TOP_LIMIT=20)} 夹取到 {@code [1,20]}
     * ——契约注释写着"不信任调用方传参"，所以缺省值之外还要夹取，这两件事都要有。
     */
    @GetMapping("/product/stat/top-sales")
    public ApiResponse<List<SpuSnapshotVO>> topSales(@RequestParam(name = "limit", defaultValue = "10") int limit) {
        return ApiResponse.ok(productStatQueryService.topBySales(limit));
    }

    private static <T> List<T> emptyIfNull(List<T> list) {
        return list == null ? List.of() : list;
    }
}
