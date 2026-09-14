package com.mall.demo.common.client;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.BusinessException;
import com.mall.demo.common.dto.HomeFeedVO;
import com.mall.demo.common.dto.SkuSnapshotVO;
import com.mall.demo.common.dto.SpuSnapshotVO;
import com.mall.demo.common.dto.StockLineVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品域**出站客户端**（P6-4 新增）：交易侧读商品、写库存的唯一出口。
 *
 * <h2>为什么必须有它</h2>
 * P6-4 之后 {@code pms_sku}/{@code pms_spu} 的属主是 {@code mall-product}（C2：单体不得再直接读写）。
 * 本类把 {@code /internal/v1/**} 的 9 个形状封成方法，供 {@code app/ProductRemoteConfig} 里的三个契约实现调用。
 *
 * <h2>为什么"单条读"也走批量端点</h2>
 * 契约里 {@code sku(id)}/{@code spu(id)} 是单条，但内部端点只有批量形状（{@code {ids:[id]}}）。
 * 规格 §4.1-1 明确**不许**为单条猜路径（猜出来的 404 会被当成"商品不存在"，是静默的展示退化）。
 * 单条 ⇒ 发一个单元素批量 ⇒ 取第一条或 null，在 {@code ProductRemoteConfig} 里做。
 *
 * <h2>错误语义（必须逐字保持，C1）</h2>
 * <ul>
 *   <li>传输异常 / 空响应 → {@code BusinessException(500, "系统繁忙，请稍后重试")}；</li>
 *   <li>下游业务码非 0 → **原样透传** {@code BusinessException(code, message)}：
 *       库存不足的 {@code 409「商品库存不足：<标题>」}必须活着到前端，不能被包成 500；</li>
 *   <li>泛型：{@code ParameterizedTypeReference} **必须由调用方显式给出**（泛型擦除 ⇒
 *       否则 Jackson 只能反序列化成 {@code LinkedHashMap}，随后 {@code ClassCastException}，编译期看不出来）。</li>
 * </ul>
 *
 * <h2>超时</h2>
 * 照 P2 实测口径：连接 300ms / 读 2500ms（下游冷启动首调实测 1.98s，写 300ms 会把正常请求判成故障）。
 */
@Slf4j
@Component
public class ProductClient {

    private static final String BASE = "/internal/v1";
    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final RestClient restClient;
    private final String internalToken;

    public ProductClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                         @Value("${mall.product.base-url:lb://mall-product}") String baseUrl,
                         @Value("${mall.internal.token:}") String internalToken,
                         @Value("${mall.product.connect-timeout-ms:300}") long connectTimeoutMs,
                         @Value("${mall.product.read-timeout-ms:2500}") long readTimeoutMs) {
        RestClient.Builder builder = baseUrl.startsWith("lb://") ? loadBalancedBuilder : RestClient.builder();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalToken = internalToken;
        // 启动日志：活体核对"商品域到底指向哪"（D2 要求能一眼看出当前接线）
        log.info("商品域客户端就绪: base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    // ==================== 只读 ====================

    /** 批量 SKU 快照（空集合直接返回空列表，**不发请求**） */
    public List<SkuSnapshotVO> skus(Collection<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        return post(BASE + "/product/sku/batch", Map.of("ids", List.copyOf(skuIds)),
                new ParameterizedTypeReference<ApiResponse<List<SkuSnapshotVO>>>() {
                });
    }

    /** 批量 SPU 快照 */
    public List<SpuSnapshotVO> spus(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return List.of();
        }
        return post(BASE + "/product/spu/batch", Map.of("ids", List.copyOf(spuIds)),
                new ParameterizedTypeReference<ApiResponse<List<SpuSnapshotVO>>>() {
                });
    }

    /**
     * 批量取"每个 SPU 下启用 SKU 的最低价"。
     *
     * <p>端点返回的是 JSON **对象**（key 是 spuId 的字符串形式，Jackson 惯例），
     * 这里显式转成 {@code Map<Long, Long>} —— 契约的调用方（收藏/足迹列表）拿的是 Long key，
     * 让它自己去 {@code Long.valueOf} 等于把一次转换错误散落到各调用点。
     */
    public Map<Long, Long> minEnabledSkuPrices(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> raw = post(BASE + "/product/sku/min-price/batch", Map.of("ids", List.copyOf(spuIds)),
                new ParameterizedTypeReference<ApiResponse<Map<String, Long>>>() {
                });
        Map<Long, Long> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            try {
                result.put(Long.valueOf(k), v);
            } catch (NumberFormatException e) {
                log.warn("忽略无法解析的 spuId key: {}", k);
            }
        });
        return result;
    }

    /** 首页商品区块（类目树 + 热门 + 新品，一次往返取全） */
    public HomeFeedVO homeFeed(int size) {
        return get(BASE + "/product/home-feed?size=" + size, new ParameterizedTypeReference<ApiResponse<HomeFeedVO>>() {
        });
    }

    /** 在架商品数（P6-4 新增端点） */
    public long enabledCount() {
        return get(BASE + "/product/stat/enabled-count", new ParameterizedTypeReference<ApiResponse<Long>>() {
        });
    }

    /** 热度榜：在架商品按销量倒序前 N（limit 由下游夹取到 [1,20]） */
    public List<SpuSnapshotVO> topBySales(int limit) {
        return get(BASE + "/product/stat/top-sales?limit=" + limit,
                new ParameterizedTypeReference<ApiResponse<List<SpuSnapshotVO>>>() {
                });
    }

    // ==================== 写（库存/销量） ====================

    /** 预占库存（失败时原样抛下游的 409「商品库存不足：<标题>」） */
    public void reserve(String orderNo, List<StockLineVO> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        post(BASE + "/stock/reserve", Map.of("orderNo", orderNo, "lines", lines),
                new ParameterizedTypeReference<ApiResponse<Void>>() {
                });
    }

    /** 回补库存（changeType 决定流水口径与正负号） */
    public void release(String orderNo, List<StockLineVO> lines, int changeType) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderNo", orderNo);
        body.put("changeType", changeType);
        body.put("lines", lines);
        post(BASE + "/stock/release", body, new ParameterizedTypeReference<ApiResponse<Void>>() {
        });
    }

    /** 累加销量（SKU 与 SPU 同时加） */
    public void incrementSales(String orderNo, List<StockLineVO> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        post(BASE + "/stock/sales/increment", Map.of("orderNo", orderNo, "lines", lines),
                new ParameterizedTypeReference<ApiResponse<Void>>() {
                });
    }

    // ==================== 内部 ====================

    private <T> T post(String uri, Object body, ParameterizedTypeReference<ApiResponse<T>> type) {
        ApiResponse<T> response;
        try {
            response = restClient.post().uri(uri)
                    .header("X-Internal-Token", internalToken)
                    .body(body)
                    .retrieve()
                    .body(type);
        } catch (Exception e) {
            log.error("调用商品域失败: uri={} body={} err={}", uri, body, e.getMessage());
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        return unwrap(uri, response);
    }

    private <T> T get(String uri, ParameterizedTypeReference<ApiResponse<T>> type) {
        ApiResponse<T> response;
        try {
            response = restClient.get().uri(uri)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(type);
        } catch (Exception e) {
            log.error("调用商品域失败: uri={} err={}", uri, e.getMessage());
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        return unwrap(uri, response);
    }

    /** 空响应按传输失败处理（宁可 500，也不要把 null 当"没有数据"）；非 0 业务码原样透传 */
    private <T> T unwrap(String uri, ApiResponse<T> response) {
        if (response == null) {
            log.error("调用商品域返回空响应: uri={}", uri);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            // 409「商品库存不足：<标题>」这类文案必须原样到前端（C1）
            throw new BusinessException(response.getCode(), response.getMessage());
        }
        return response.getData();
    }
}
