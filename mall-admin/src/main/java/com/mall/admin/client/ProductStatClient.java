package com.mall.admin.client;

import com.mall.admin.support.ApiResponse;
import com.mall.admin.support.BusinessException;
import com.mall.admin.support.dto.SpuSnapshotVO;
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
 * <b>商品域</b>（{@code mall-product}）出站客户端：看板要的两个商品口径数 + 销售额榜的标题/主图。
 *
 * <h2>为什么只调三个形状</h2>
 * 看板对商品域只问两件事：
 * <ol>
 *   <li>"有多少在架商品"（{@code GET /internal/v1/product/stat/enabled-count}）——
 *       summary 的 {@code onShelfProductCount}；</li>
 *   <li>"销量前 N 是谁"（{@code GET /internal/v1/product/stat/top-sales}）——
 *       {@code top?type=sales}；</li>
 *   <li>外加"按 id 批量补标题/主图"（{@code POST /internal/v1/product/spu/batch}）——
 *       {@code top?type=amount} 时，金额由交易域给（有序），展示字段必须由商品域补
 *       （单体 {@code AdminDashboardServiceImpl.topByAmount} 就是这么分工的）。</li>
 * </ol>
 *
 * <p>⚠️ 这两条 {@code stat} 端点是 P6-4 为后台商品管理加的（当时是给单体薄转发用的），
 * 本批改成由 BFF 直接消费——**端点没变、调用方变了**，所以 C1 也不受影响。
 *
 * <p>错误语义、日志纪律、泛型注意事项与 {@link TradeStatClient} 完全一致（同一个口径，不另立一套）。
 */
@Slf4j
@Component
public class ProductStatClient {

    private static final String BASE = "/internal/v1";
    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final RestClient restClient;
    private final String internalToken;

    public ProductStatClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
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
        log.info("商品域客户端就绪: base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /** 在架商品数（summary 的 {@code onShelfProductCount}） */
    public long enabledCount() {
        Long count = get("/product/stat/enabled-count", new ParameterizedTypeReference<ApiResponse<Long>>() {
        });
        return count == null ? 0L : count;
    }

    /** 销量榜：在架商品按销量倒序前 N（limit 由商品域夹取到 [1,20]） */
    public List<SpuSnapshotVO> topBySales(int limit) {
        List<SpuSnapshotVO> rows = get("/product/stat/top-sales?limit=" + limit,
                new ParameterizedTypeReference<ApiResponse<List<SpuSnapshotVO>>>() {
                });
        return rows == null ? List.of() : rows;
    }

    /**
     * 批量 SPU 快照（销售额榜补 {@code title/mainImage}）。
     *
     * <p>空集合 → 空 Map 且**不发请求**（与单体 {@code ProductClient.spus} 同口径）。
     * 返回 Map 而不是 List：调用方是"按 spuId 查标题"，顺序无意义（榜单顺序来自交易域）。
     */
    public Map<Long, SpuSnapshotVO> spus(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return Map.of();
        }
        List<SpuSnapshotVO> rows = post("/product/spu/batch", Map.of("ids", List.copyOf(spuIds)),
                new ParameterizedTypeReference<ApiResponse<List<SpuSnapshotVO>>>() {
                });
        Map<Long, SpuSnapshotVO> result = new LinkedHashMap<>();
        if (rows == null) {
            return result;
        }
        for (SpuSnapshotVO spu : rows) {
            if (spu != null && spu.getId() != null) {
                result.put(spu.getId(), spu);
            }
        }
        return result;
    }

    // ==================== 内部（口径同 TradeStatClient） ====================

    private <T> T get(String uri, ParameterizedTypeReference<ApiResponse<T>> type) {
        ApiResponse<T> response;
        try {
            response = restClient.get().uri(BASE + uri)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(type);
        } catch (Exception e) {
            log.debug("调用商品域失败: uri={} err={}", uri, e.toString());
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        return unwrap(uri, response);
    }

    private <T> T post(String path, Object body, ParameterizedTypeReference<ApiResponse<T>> type) {
        ApiResponse<T> response;
        try {
            response = restClient.post().uri(BASE + path)
                    .header("X-Internal-Token", internalToken)
                    .body(body)
                    .retrieve()
                    .body(type);
        } catch (Exception e) {
            log.debug("调用商品域失败: path={} err={}", path, e.toString());
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        return unwrap(path, response);
    }

    private <T> T unwrap(String uri, ApiResponse<T> response) {
        if (response == null) {
            log.debug("调用商品域返回空响应: uri={}", uri);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            throw new BusinessException(response.getCode(), response.getMessage());
        }
        return response.getData();
    }
}
