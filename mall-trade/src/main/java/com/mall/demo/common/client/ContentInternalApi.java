package com.mall.demo.common.client;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.PageResult;
import com.mall.demo.common.dto.AdminBannerVO;
import com.mall.demo.common.dto.AdminNoticeVO;
import com.mall.demo.common.dto.BannerSaveRequest;
import com.mall.demo.common.dto.NoticeSaveRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

import java.util.Map;

/**
 * <b>内容域内部契约的声明式接口</b>（Spring HTTP Interface，替换
 * {@link ContentInternalClient} 里原先手写的 {@code RestClient} 链）。
 *
 * <h2>这一层做什么 / 不做什么</h2>
 * <ul>
 *   <li><b>做</b>：路径与 HTTP 动词只写一次；查询参数用 {@code @RequestParam}、路径变量用
 *       {@code @PathVariable}（不再有 {@code "/banner/" + id + "/status"} 这种拼接）；
 *       返回类型是**方法签名的一部分** ⇒ 泛型不再靠人工传 {@code ParameterizedTypeReference}
 *       （泛型擦除导致的 {@code LinkedHashMap} 强转失败结构性消失）。</li>
 *   <li><b>不做</b>：错误语义（传输异常 → 500「系统繁忙，请稍后重试」、业务码非 0 → 原样透传
 *       {@code BusinessException(code, message)}）、空响应判定、日志，全留在
 *       {@link ContentInternalClient}（那是"域语义"，不是"HTTP 形状"）。</li>
 * </ul>
 *
 * <p>⚠️ 返回类型是**下游的原始响应体** {@code ApiResponse<T>}（不是 {@code T}）：解包统一在客户端类里做。
 *
 * <p>⚠️ 出站头 {@code X-Internal-Token} / {@code X-Trace-Id} 由挂在 {@code @LoadBalanced
 * RestClient.Builder}（或直连分支的）{@code OutboundHeadersInterceptor} 统一注入，这里与客户端类
 * 都**不再**手工写 {@code .header(...)}。
 */
@HttpExchange(url = "/internal/v1/content", contentType = "application/json")
public interface ContentInternalApi {

    // ==================== 轮播 ====================

    /** 轮播分页（{@code keyword}/{@code status} 为空 ⇒ **不发送该查询参数**，与迁移前 queryParamIfPresent 同口径） */
    @GetExchange("/banner/page")
    ApiResponse<PageResult<AdminBannerVO>> bannerPage(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam("pageNum") long pageNum,
            @RequestParam("pageSize") long pageSize);

    @PostExchange("/banner")
    ApiResponse<Long> createBanner(@RequestBody BannerSaveRequest request);

    @PutExchange("/banner/{id}")
    ApiResponse<Void> updateBanner(@PathVariable("id") Long id, @RequestBody BannerSaveRequest request);

    /** 启停：下游收的是 {@code {"status": …}} 这个小对象（不是查询参数） */
    @PutExchange("/banner/{id}/status")
    ApiResponse<Void> updateBannerStatus(@PathVariable("id") Long id, @RequestBody Map<String, Object> body);

    @DeleteExchange("/banner/{id}")
    ApiResponse<Void> deleteBanner(@PathVariable("id") Long id);

    // ==================== 公告 ====================

    @GetExchange("/notice/page")
    ApiResponse<PageResult<AdminNoticeVO>> noticePage(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam("pageNum") long pageNum,
            @RequestParam("pageSize") long pageSize);

    @PostExchange("/notice")
    ApiResponse<Long> createNotice(@RequestBody NoticeSaveRequest request);

    @PutExchange("/notice/{id}")
    ApiResponse<Void> updateNotice(@PathVariable("id") Long id, @RequestBody NoticeSaveRequest request);

    @PutExchange("/notice/{id}/status")
    ApiResponse<Void> updateNoticeStatus(@PathVariable("id") Long id, @RequestBody Map<String, Object> body);

    @DeleteExchange("/notice/{id}")
    ApiResponse<Void> deleteNotice(@PathVariable("id") Long id);

    // ==================== 文件上传 ====================

    /**
     * multipart 文件上传（{@code MinIO} 在内容域）。
     *
     * <p>形状与迁移前**逐字保持**：报文仍是
     * {@code MultiValueMap<String,Object>}（key {@code file}，值是带 filename 的
     * {@code ByteArrayResource}）。迁移前的写法是
     * {@code .contentType(MULTIPART_FORM_DATA).body(multiValueMap)}；这里的
     * {@code contentType = "multipart/form-data"}（**方法级**，覆盖类级的 application/json）
     * 让 {@code RestClientAdapter} 走同一条路径——先把 Content-Type 写进请求头，再把同一个
     * map 交给 {@code FormHttpMessageConverter} 落盘（边界与各 part 头都由同一个转换器生成）。
     * 因此**没有**退化成"把 map 当 JSON 发"。
     */
    @PostExchange(value = "/upload", contentType = "multipart/form-data")
    ApiResponse<Map<String, Object>> upload(@RequestBody MultiValueMap<String, Object> body);
}
