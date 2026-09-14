package com.mall.search.client;

import com.mall.search.dto.IndexDocsResult;
import com.mall.search.support.ApiResponse;
import com.mall.search.support.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 商品域出站客户端：**索引文档内容的唯一来源**。
 *
 * <p>路径与形状对齐 product 的 {@code POST /internal/v1/product/index-docs}
 * （请求 {@code {spuIds:[...]}} / {@code {brandId:N}} / {@code {pageNum,pageSize}}，
 * 响应 {@code ApiResponse<IndexDocsResult>}）。
 *
 * <p><b>为什么必须走契约而不是查表</b>（规格 §3 的核心）：本服务**没有 MySQL**。
 * 索引文档的字段口径（在架 SKU 的最低价/总库存聚合、品牌名、{@code createTimeMillis}）
 * 是**商品域的知识**，只能问属主。这也是"不能读旧库、写新索引"这条纪律的落点。
 *
 * <p>错误处理口径照抄 mall-marketing 的 {@code UserCenterMemberClient}（不另立一套）：
 * <ul>
 *   <li>业务码非 0 → {@link BusinessException} 原样透传（下游的 404/400 文案不能变成 500）；</li>
 *   <li>传输失败 / 空响应 → 500「系统繁忙，请稍后重试」（与单体同一文案）。</li>
 * </ul>
 *
 * <p>⚠️ **刻意不做本地兜底**（规格 §6 最后一行）：拉不到内容就**明确失败**，
 * 绝不用"空文档"顶替——那会把商品从索引里抹掉，而且不报错。
 * 调用方的处理见 {@code ProductSearchServiceImpl#reindex}（失败即抛出，不写半截索引）。
 *
 * <p>⚠️ 超时按 P2 的实测口径：连接 300ms / 读 2500ms（下游冷启动首调 1.98s，
 * 照抄"目标值 300ms"会把正常请求判成故障）。
 */
@Slf4j
@Component
public class ProductIndexDocClient {

    private static final String BASE = "/internal/v1/product";
    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final RestClient restClient;
    private final String internalToken;

    public ProductIndexDocClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
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

    /**
     * 按 spuId 取索引文档（**只返回在架且未删除的**）。
     *
     * <p>空集合 → 空结果且**不发起调用**（空批量没有意义，下游对空集合的返回值也无从校验）。
     */
    public IndexDocsResult bySpuIds(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return new IndexDocsResult(0, List.of());
        }
        IndexDocsResult result = post(Map.of("spuIds", List.copyOf(spuIds)));
        return result == null ? new IndexDocsResult(0, List.of()) : result;
    }

    /** 按品牌取"该品牌下在架商品"的索引文档（{@code syncByBrand} 用） */
    public IndexDocsResult byBrand(long brandId) {
        IndexDocsResult result = post(Map.of("brandId", brandId));
        return result == null ? new IndexDocsResult(0, List.of()) : result;
    }

    /**
     * 分页取全量在架商品的索引文档（{@code reindex} 用）。
     *
     * @param pageNum  从 1 开始（与前台分页口径一致）
     * @param pageSize 每页条数（调用方控制，见 {@code ProductSearchServiceImpl#reindex}）
     */
    public IndexDocsResult page(long pageNum, long pageSize) {
        IndexDocsResult result = post(Map.of("pageNum", pageNum, "pageSize", pageSize));
        return result == null ? new IndexDocsResult(0, List.of()) : result;
    }

    // ==================== 内部 ====================

    /**
     * ⚠️ 具体类型必须由调用方显式传入（见下面的 {@code ParameterizedTypeReference}）：
     * 泛型方法里的 {@code T} 会被擦除，Jackson 只能反序列化成 {@code LinkedHashMap}，
     * 调用方随后 {@code ClassCastException} —— 这个 bug 编译期看不出来（P3-4 实测踩到）。
     */
    private IndexDocsResult post(Object body) {
        ApiResponse<IndexDocsResult> response;
        try {
            response = restClient.post()
                    .uri(BASE + "/index-docs")
                    .header("X-Internal-Token", internalToken)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<IndexDocsResult>>() {
                    });
        } catch (Exception e) {
            log.error("调用商品域取索引文档失败: body={}", body, e);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response == null) {
            log.error("调用商品域取索引文档返回空响应: body={}", body);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            throw new BusinessException(response.getCode(), response.getMessage());
        }
        return response.getData();
    }
}
