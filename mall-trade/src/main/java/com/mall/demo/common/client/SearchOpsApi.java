package com.mall.demo.common.client;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.dto.ReindexResult;
import com.mall.demo.common.dto.SearchStatusVO;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * <b>检索域运维契约的声明式接口</b>（Spring HTTP Interface，替换 {@link SearchOpsClient} 里原先手写的
 * {@code RestClient} 链）。
 *
 * <ul>
 *   <li>{@code POST /internal/v1/search/reindex} —— 全量重建索引（取数走 product 的契约，写入由 search 负责，
 *       单体不再有任何"写索引"的能力，见 D5 索引双写者收口）；</li>
 *   <li>{@code GET /internal/v1/search/status} —— 自检快照（ES 客户端已经不在单体里，五个集群字段由
 *       {@code mall-search} 提供）。</li>
 * </ul>
 *
 * <p>错误语义**不在这里**：{@link SearchOpsClient#reindex()} 的"下游业务码原样透传"与
 * {@link SearchOpsClient#status()} 的"异常消息带真实原因（检索域不可达: …）"都是刻意不同的两套口径，
 * 必须留在客户端类里，故这里只声明 HTTP 形状。
 *
 * <p>⚠️ 出站头 {@code X-Internal-Token} / {@code X-Trace-Id} 由 {@code OutboundHeadersInterceptor}
 * 统一注入。
 */
@HttpExchange(url = "/internal/v1/search", contentType = "application/json")
public interface SearchOpsApi {

    /** 全量重建索引（重建期间检索不可用，这是既有语义；不做重试、不做降级） */
    @PostExchange("/reindex")
    ApiResponse<ReindexResult> reindex();

    /** 自检快照（检索域活着但连不上 ES 时仍会正常返回，集群字段为 null/-1，由调用方区分两种"不可用"） */
    @GetExchange("/status")
    ApiResponse<SearchStatusVO> status();
}
