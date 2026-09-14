package com.mall.demo.internal;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.dto.HomeFeedVO;
import com.mall.demo.common.dto.SkuSnapshotVO;
import com.mall.demo.common.dto.SpuSnapshotVO;
import com.mall.demo.common.dto.StockLineVO;
import com.mall.demo.pms.service.ProductQueryService;
import com.mall.demo.pms.service.StockCommandService;
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
 * <p>⚠️ 这几个写端点将来是"跨库写"的入口（§1.3 最严重的三处），因此：
 * 调用方必须按 {@code orderNo} 幂等、按 {@code skuId} 升序传入，
 * 且实现内部的"单条 SQL 防超卖 / 按 skuId 加锁 / 更新后再读写流水"细节不得因为跨了 HTTP 而简化。
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

    private final ProductQueryService productQueryService;
    private final StockCommandService stockCommandService;

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
     * <p>调用方是 {@code mall-content} 的首页聚合（§2.9）：它必须**一次**拿到三块，
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

    private static <T> List<T> emptyIfNull(List<T> list) {
        return list == null ? List.of() : list;
    }
}
