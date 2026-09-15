package com.mall.demo.common.client;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.dto.HomeFeedVO;
import com.mall.demo.common.dto.SkuSnapshotVO;
import com.mall.demo.common.dto.SpuSnapshotVO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.Map;

/**
 * <b>商品域内部契约的声明式接口</b>（Spring HTTP Interface，替换 {@link ProductClient} 里原先手写的
 * {@code RestClient} 链）。
 *
 * <h2>端点形状（与 {@code /internal/v1/**} 的 9 个形状一一对应）</h2>
 * <ul>
 *   <li>只读：{@code POST /product/sku/batch}、{@code POST /product/spu/batch}、
 *       {@code POST /product/sku/min-price/batch}、{@code GET /product/home-feed}、
 *       {@code GET /product/stat/enabled-count}、{@code GET /product/stat/top-sales}</li>
 *   <li>写：{@code POST /stock/reserve}、{@code POST /stock/release}、{@code POST /stock/sales/increment}</li>
 * </ul>
 *
 * <p>批量端点的请求体一律是 {@code {ids:[…]}} 或 {@code {orderNo,lines,…}}，这里声明成
 * {@code Map<String,Object>}：**单条读也走批量端点**（规格 §4.1-1 明确不许为单条猜路径——
 * 猜出来的 404 会被当成"商品不存在"，是静默的展示退化），拼单元素批量在客户端/契约映射里做。
 *
 * <h2>这一层刻意不做的事</h2>
 * 错误语义（传输异常/空响应 → 500「系统繁忙，请稍后重试」、业务码非 0 → **原样透传**
 * {@code BusinessException(code, message)}）、空集合不发调用、{@code Map<String,…>} 的字符串 key → Long
 * 转换，全部留在 {@link ProductClient}——那些是"域语义与数据形状适配"，不是"HTTP 形状"。
 *
 * <p>⚠️ 返回类型是**下游的原始响应体** {@code ApiResponse<T>}（不是 {@code T}）：泛型由方法签名固定，
 * 不再需要人工传 {@code ParameterizedTypeReference}（擦除导致 {@code LinkedHashMap} 强转失败的那个坑
 * 结构性消失）。
 *
 * <p>⚠️ 出站头 {@code X-Internal-Token} / {@code X-Trace-Id} 由 {@code OutboundHeadersInterceptor}
 * 统一注入，这里与客户端类都**不再**手工写 {@code .header(...)}。
 */
@HttpExchange(url = "/internal/v1", contentType = "application/json")
public interface ProductApi {

    // ==================== 只读 ====================

    /** 批量 SKU 快照；body {@code {ids:[…]}} */
    @PostExchange("/product/sku/batch")
    ApiResponse<List<SkuSnapshotVO>> skus(@RequestBody Map<String, Object> body);

    /** 批量 SPU 快照；body {@code {ids:[…]}} */
    @PostExchange("/product/spu/batch")
    ApiResponse<List<SpuSnapshotVO>> spus(@RequestBody Map<String, Object> body);

    /**
     * 批量取"每个 SPU 下启用 SKU 的最低价"；body {@code {ids:[…]}}。
     *
     * <p>下游返回 JSON **对象**（key 是 spuId 的字符串形式）⇒ 这里就声明成 {@code Map<String, Long>}，
     * "字符串 key → Long"的转换留在客户端类里做一次。
     */
    @PostExchange("/product/sku/min-price/batch")
    ApiResponse<Map<String, Long>> minEnabledSkuPrices(@RequestBody Map<String, Object> body);

    /** 首页商品区块（类目树 + 热门 + 新品，一次往返取全） */
    @GetExchange("/product/home-feed")
    ApiResponse<HomeFeedVO> homeFeed(@RequestParam("size") int size);

    /** 在架商品数 */
    @GetExchange("/product/stat/enabled-count")
    ApiResponse<Long> enabledCount();

    /** 热度榜：在架商品按销量倒序前 N（limit 由下游夹取到 [1,20]） */
    @GetExchange("/product/stat/top-sales")
    ApiResponse<List<SpuSnapshotVO>> topBySales(@RequestParam("limit") int limit);

    // ==================== 写（库存/销量） ====================

    /** 预占库存；body {@code {orderNo,lines}}（失败时下游抛 409「商品库存不足：<标题>」） */
    @PostExchange("/stock/reserve")
    ApiResponse<Void> reserve(@RequestBody Map<String, Object> body);

    /** 回补库存；body {@code {orderNo,changeType,lines}}（changeType 决定流水口径与正负号） */
    @PostExchange("/stock/release")
    ApiResponse<Void> release(@RequestBody Map<String, Object> body);

    /** 累加销量（SKU 与 SPU 同时加）；body {@code {orderNo,lines}} */
    @PostExchange("/stock/sales/increment")
    ApiResponse<Void> incrementSales(@RequestBody Map<String, Object> body);
}
