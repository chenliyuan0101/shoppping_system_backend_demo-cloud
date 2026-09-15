package com.mall.search.client;

import com.mall.search.config.OutboundRestClientFactory;
import com.mall.search.dto.IndexDocsResult;
import com.mall.search.support.ApiResponse;
import com.mall.search.support.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

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
 * <h2>P8-7 起：HTTP 调用改由声明式接口 {@link ProductIndexDocApi} 承担</h2>
 * 本类保留**域语义**四件事，其余交给接口：
 * <ul>
 *   <li><b>连接装配</b>：{@code lb://} 走服务发现（{@code @LoadBalanced} 的 builder，其上已挂
 *       {@code OutboundHeadersInterceptor} 统一注入 {@code X-Internal-Token} / {@code X-Trace-Id}）、
 *       {@code http://} 直连（排障/演练用，由 {@link OutboundRestClientFactory} 显式挂同一个拦截器 ⇒
 *       **直连也带内部令牌**），并设置 connect/read 超时；</li>
 *   <li><b>错误语义</b>：传输失败 / 空响应 → {@code BusinessException(500, "系统繁忙，请稍后重试")}；
 *       业务码非 0 → **原样透传**（下游的 404/400 文案不能变成 500）；</li>
 *   <li><b>形状适配</b>："空集合不发请求"（空批量没有意义，下游对空集合的返回值也无从校验）、
 *       {@code data} 为 {@code null} 时按"空文档集"处理；</li>
 *   <li><b>失败即 ERROR 日志</b>（带请求体）：本类的失败是**明确的失败**，不是预期内的降级，
 *       日志级别由本域自己定（与 mall-admin 的看板客户端"只 debug"刻意不同）。</li>
 * </ul>
 *
 * <p>错误处理口径照抄 mall-marketing 的 {@code UserCenterMemberClient}（不另立一套）：
 * <ul>
 *   <li>业务码非 0 → {@link BusinessException} 原样透传；</li>
 *   <li>传输失败 / 空响应 → 500「系统繁忙，请稍后重试」（与单体同一文案）。</li>
 * </ul>
 *
 * <p>⚠️ **刻意不做本地兜底**（规格 §6 最后一行）：拉不到内容就**明确失败**，
 * 绝不用"空文档"顶替——那会把商品从索引里抹掉，而且不报错。
 * 调用方的处理见 {@code ProductSearchServiceImpl#reindex}（失败即抛出，不写半截索引）。
 *
 * <p>⚠️ 超时按 P2 的实测口径：连接 300ms / 读 2500ms（下游冷启动首调 1.98s，
 * 照抄"目标值 300ms"会把正常请求判成故障）。
 *
 * <p>⚠️ 路径前缀 {@code /internal/**}：网关有过滤器直接 404 这一前缀（外网永远看不到）。
 */
@Slf4j
@Component
public class ProductIndexDocClient {

    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final ProductIndexDocApi api;

    public ProductIndexDocClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                                 OutboundRestClientFactory restClients,
                                 @Value("${mall.product.base-url:lb://mall-product}") String baseUrl,
                                 @Value("${mall.product.connect-timeout-ms:300}") long connectTimeoutMs,
                                 @Value("${mall.product.read-timeout-ms:2500}") long readTimeoutMs) {
        RestClient restClient = restClients.build(loadBalancedBuilder, baseUrl, connectTimeoutMs, readTimeoutMs);
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(ProductIndexDocApi.class);
        // 启动日志：活体核对"商品域到底指向哪"（排查"索引为什么拉不到内容"的第一行）
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
     * 发一次 {@code POST /internal/v1/product/index-docs} 并把"HTTP 形状"收敛成域语义。
     *
     * <p>泛型不再需要人工传 {@code ParameterizedTypeReference}：返回类型
     * {@code ApiResponse<IndexDocsResult>} 写在 {@link ProductIndexDocApi} 的方法签名上，
     * 框架从 {@code MethodParameter} 解析泛型（原先那处匿名子类正是为了绕开"泛型被擦除 ⇒
     * data 反序列化成 LinkedHashMap ⇒ 调用方 ClassCastException"，且编译期看不出来）。
     *
     * <p>为什么还要这层 try/catch：声明式接口解决的是"HTTP ↔ 类型"的样板，**不解决错误语义**——
     * 连接被拒/超时/读超时抛的是 {@code ResourceAccessException}，4xx/5xx 抛的是
     * {@code HttpClientErrorException}（RestClient 默认状态处理器），两者都要在这里变成"域的失败"。
     */
    private IndexDocsResult post(Map<String, Object> body) {
        ApiResponse<IndexDocsResult> response;
        try {
            response = api.indexDocs(body);
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
