package com.mall.trade.common.client;

import com.mall.trade.common.ApiResponse;
import com.mall.trade.common.BusinessException;
import com.mall.trade.common.dto.ReindexResult;
import com.mall.trade.common.dto.SearchStatusVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import com.mall.common.client.OutboundRestClientFactory;

/**
 * 检索域**运维**客户端：把"重建索引"与"自检快照"转给 {@code mall-search}。
 *
 * <h2>为什么必须有它（D5：索引双写者收口）</h2>
 * P6-4 之前，单体的 {@code AdminEsController#reindex()} 调的是**本地的** {@code ProductSearchService.reindex()}，
 * 而那份实现的取数源是 {@code mall} 库（冻结副本）。切流量之后再用它，就等于
 * <b>把旧数据重新写回新索引</b> —— 这正是 `P6-4-switch-plan.md` §四点六 实测到的那类事故
 * （索引被单体按旧值覆盖，而 search 侧看不出任何异常）。
 * 所以这里改成转发到 {@code mall-search} 的 {@code POST /internal/v1/search/reindex}：
 * 取数走 product 的契约（真值），写入由 search 负责，**单体不再有任何"写索引"的能力**。
 *
 * <h2>P6-5 #6：自检也改成转发（本类从"只有一个方法"变成两个）</h2>
 * 原文（P6-4 版）写着"`ping()` 是只读，不构成第二个写方，D5 保持本地只读 ⇒ 不需要往这里加方法"。
 * 那条判断在**只读**这个维度上没错，但漏了另一件事：只要 {@code AdminEsController} 还注入
 * {@code ElasticsearchClient}，单体就**仍然持有 ES 客户端**（依赖、配置、证书、连接池全都在），
 * P6-6 就没法干净地删掉 {@code com.mall.trade.pms} 整包。所以本批把 {@code ping} 也改成转发：
 * 单体侧的自检变成"问检索域要一份快照"，五个集群字段由 {@code mall-search} 提供
 * （见 {@code SearchStatusVO}）。
 *
 * <h2>HTTP 调用改由声明式接口 {@link SearchOpsApi} 承担</h2>
 * 路径与动词集中写在接口里；本类保留两套**刻意不同**的错误语义（改接口时一字未动）：
 * <ul>
 *   <li>{@link #reindex()}：传输异常/空响应 → {@code 500「系统繁忙，请稍后重试」}；
 *       下游业务码（例如 search 侧"取不满"的 500 文案）**原样透传** —— 那正是运维需要看到的原始原因，
 *       重新包装成"系统繁忙"会把"取不满（2/5）"这种关键信息抹掉。</li>
 *   <li>{@link #status()}：同样抛 {@link BusinessException}，但**消息带上真实原因**
 *       （例如 {@code 检索域不可达: Connection refused}）—— 自检接口要把原因写进
 *       {@code error} 字段给运维看，包装成"系统繁忙"等于把唯一的线索丢掉。</li>
 * </ul>
 * 内部密钥 {@code X-Internal-Token} 不再手工写：{@code lb://} 走 {@code @LoadBalanced} builder 上的
 * {@code OutboundHeadersInterceptor}，{@code http://} 直连由
 * {@link OutboundRestClientFactory} 显式挂同一个拦截器。
 */
@Slf4j
@Component
public class SearchOpsClient {

    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final SearchOpsApi api;

    public SearchOpsClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                           OutboundRestClientFactory restClients,
                           @Value("${mall.search.base-url:lb://mall-search}") String baseUrl,
                           @Value("${mall.search.connect-timeout-ms:300}") long connectTimeoutMs,
                           @Value("${mall.search.read-timeout-ms:2500}") long readTimeoutMs) {
        // 与 mall-product 的检索客户端同口径：lb:// 走服务发现，直连地址则不走（便于把地址指到别处做演练）
        RestClient restClient = restClients.build(loadBalancedBuilder, baseUrl, connectTimeoutMs, readTimeoutMs);
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(SearchOpsApi.class);
        // 打印当前指向，便于活体核对"重建到底转发到哪了"（与 D2 要求的启动日志同一用途）
        log.info("检索域运维客户端就绪(HTTP Interface): base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 全量重建索引（转发到 {@code mall-search}）。
     *
     * <p>⚠️ 重建期间检索不可用（search 侧是"删索引→建索引→写回"），这是既有语义；
     * 本方法**不**做重试、不做降级（运维动作要如实失败，不能假装成功）。
     */
    public ReindexResult reindex() {
        ApiResponse<ReindexResult> response;
        try {
            response = api.reindex();
        } catch (Exception e) {
            log.error("调用检索域重建索引失败: {}", e.getMessage(), e);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response == null) {
            log.error("调用检索域重建索引返回空响应");
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            // 原样透传：search 侧的"商品索引重建失败：文档取不满（2/5）"这类文案正是运维要看的
            log.warn("检索域重建索引返回业务失败: code={} message={}", response.getCode(), response.getMessage());
            throw new BusinessException(response.getCode(), response.getMessage());
        }
        return response.getData();
    }

    /**
     * 自检快照（转发到 {@code mall-search} 的 {@code GET /internal/v1/search/status}）。
     *
     * <p>⚠️ 与"ES 不可用"是**两件事**，调用方必须分开处理（{@code AdminEsController#ping} 就是这么做的）：
     * <ul>
     *   <li>本方法**抛异常**：检索域不可达/超时/返回非 0 码 ⇒ 连"快照"都拿不到（error 里写原因）；</li>
     *   <li>本方法**正常返回**但集群字段为 {@code null}/{@code -1}：检索域活着，是它那边连不上 ES
     *       ⇒ 同样要报 {@code available=false}，但原因是 ES，不是检索域。</li>
     * </ul>
     */
    public SearchStatusVO status() {
        ApiResponse<SearchStatusVO> response;
        try {
            response = api.status();
        } catch (Exception e) {
            log.warn("调用检索域自检失败: {}", e.getMessage());
            // ⚠️ 与 reindex 不同：这里把**真实原因**带出去（自检接口要把它展示给运维）
            throw new BusinessException(500, "检索域不可达: " + e.getMessage());
        }
        if (response == null || response.getData() == null) {
            log.warn("检索域自检返回空响应或空数据");
            throw new BusinessException(500, "检索域自检返回空数据");
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            log.warn("检索域自检返回业务失败: code={} message={}", response.getCode(), response.getMessage());
            throw new BusinessException(response.getCode(), response.getMessage());
        }
        return response.getData();
    }
}
