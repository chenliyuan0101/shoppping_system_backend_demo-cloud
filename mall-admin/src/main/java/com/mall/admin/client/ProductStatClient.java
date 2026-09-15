package com.mall.admin.client;

import com.mall.common.client.OutboundRestClientFactory;
import com.mall.admin.support.ApiResponse;
import com.mall.admin.support.BusinessException;
import com.mall.admin.support.dto.SpuSnapshotVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

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
 * <h2>P8-7 起：HTTP 调用改由声明式接口 {@link ProductStatApi} 承担</h2>
 * 本类保留**域语义**三件事，其余交给接口：
 * <ul>
 *   <li><b>连接装配</b>：{@code lb://} 走服务发现（{@code @LoadBalanced} 的 builder，其上已挂
 *       {@code OutboundHeadersInterceptor} 统一注入 {@code X-Internal-Token} / {@code X-Trace-Id}）、
 *       {@code http://} 直连（排障/演练用），并设置 connect/read 超时；</li>
 *   <li><b>错误语义</b>：传输异常/空响应 → {@code BusinessException(500, 系统繁忙，请稍后重试)}；
 *       下游业务码非 0 → **原样透传** {@code BusinessException(code, message)}；</li>
 *   <li><b>数据形状适配</b>：批量端点把 {@code List<SpuSnapshotVO>} 转成 {@code Map<Long, SpuSnapshotVO>}
 *       （空集合直接返回空 Map 且**不发请求**）。</li>
 * </ul>
 *
 * <p>错误语义、日志纪律、泛型注意事项与 {@link TradeStatClient} 完全一致（同一个口径，不另立一套）。
 *
 * <p>⚠️ 路径前缀 {@code /internal/**}：网关有过滤器直接 404 这一前缀（外网永远看不到）。
 */
@Slf4j
@Component
public class ProductStatClient {

    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final ProductStatApi api;

    public ProductStatClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                             OutboundRestClientFactory restClients,
                             @Value("${mall.product.base-url:lb://mall-product}") String baseUrl,
                             @Value("${mall.product.connect-timeout-ms:300}") long connectTimeoutMs,
                             @Value("${mall.product.read-timeout-ms:2500}") long readTimeoutMs) {
        RestClient restClient = restClients.build(loadBalancedBuilder, baseUrl, connectTimeoutMs, readTimeoutMs);
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(ProductStatApi.class);
        // 启动日志：活体核对"商品域到底指向哪"（排查"看板商品数为什么全是 0"的第一行）
        log.info("商品域客户端就绪(HTTP Interface): base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /** 在架商品数（summary 的 {@code onShelfProductCount}） */
    public long enabledCount() {
        Long count = unwrap("product/stat/enabled-count", call(api::enabledCount));
        return count == null ? 0L : count;
    }

    /** 销量榜：在架商品按销量倒序前 N（limit 由商品域夹取到 [1,20]） */
    public List<SpuSnapshotVO> topBySales(int limit) {
        List<SpuSnapshotVO> rows =
                unwrap("product/stat/top-sales", call(() -> api.topBySales(limit)));
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
        List<SpuSnapshotVO> rows =
                unwrap("product/spu/batch", call(() -> api.spus(Map.of("ids", List.copyOf(spuIds)))));
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

    /**
     * 把"调用接口"这一步的**传输异常**收敛成统一文案。
     *
     * <p>为什么还要这层 try/catch：声明式接口解决的是"HTTP ↔ 类型"的样板，**不解决错误语义**——
     * 连接被拒/超时/读超时抛的是 {@code ResourceAccessException}，非 0 业务码抛的是
     * {@code HttpClientErrorException}（RestClient 默认状态处理器在 4xx/5xx 上抛），
     * 两者都要在这里变成"域的失败"，调用方才知道"这个依赖不可用"。
     */
    private <T> ApiResponse<T> call(java.util.function.Supplier<ApiResponse<T>> invocation) {
        try {
            return invocation.get();
        } catch (Exception e) {
            // debug 而不是 error：看板预期内的降级不该刷错误栈（级别由调用方定，见类注释）
            log.debug("调用商品域失败: err={}", e.toString());
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
    }

    /** 空响应按传输失败处理（宁可 500，也不要把 null 当"没有数据"）；非 0 业务码原样透传 */
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
