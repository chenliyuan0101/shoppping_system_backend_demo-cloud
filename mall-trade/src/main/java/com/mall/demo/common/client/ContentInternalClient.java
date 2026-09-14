package com.mall.demo.common.client;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.BusinessException;
import com.mall.demo.common.PageResult;
import com.mall.demo.common.dto.AdminBannerVO;
import com.mall.demo.common.dto.AdminNoticeVO;
import com.mall.demo.common.dto.BannerSaveRequest;
import com.mall.demo.common.dto.NoticeSaveRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 内容域出站客户端：单体把内容域的管理端写操作与文件上传<b>转发</b>给 {@code mall-content}。
 *
 * <p><b>为什么是"转发"而不是"把接口搬过去"（P2 决策 1）</b>：这几个端点都带登录态，
 * 而校验登录态要读管理员账号表（属认证域 {@code mall-admin}）与 {@code ums_member}（属 user-center，P3）。
 * 让内容域自己校验，就等于让它直连别人的表（违背 C2）或提前落地 P3 的登录态改造。
 * 于是单体继续当"鉴权 + 对外契约"的门面，业务与数据在内容域——**这是过渡形态**，
 * 退场条件（P3 网关验签 / P7 管理端 BFF）写在方案 §5 P2 决策 1。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>错误透传</b>：内容域返回的 {@code code/message} 原样抛成 {@link BusinessException}，
 *       由 {@code GlobalExceptionHandler} 变成同样的响应体——客户端看不到任何差异（C1）；</li>
 *   <li><b>下游不可用 → 500</b>：写操作是"关键路径"，不能像首页那样降级成空数据；
 *       统一成既有的 {@code 500 系统繁忙，请稍后重试}，真实原因进日志；</li>
 *   <li><b>带内部密钥</b>：{@code X-Internal-Token}（网关不路由 {@code /internal/**}，见 §4.10）。</li>
 * </ol>
 */
@Slf4j
@Component
public class ContentInternalClient {

    /** 内容域内部接口前缀（与 {@code mall-content} 的 InternalXxxController 一一对应） */
    private static final String BASE = "/internal/v1/content";

    /** 下游不可用时的兜底文案：与 GlobalExceptionHandler 的 500 文案逐字一致 */
    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final RestClient restClient;
    private final String internalToken;

    public ContentInternalClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                                 @Value("${mall.content.base-url:lb://mall-content}") String baseUrl,
                                 @Value("${mall.internal.token:}") String internalToken,
                                 @Value("${mall.content.connect-timeout-ms:200}") long connectTimeoutMs,
                                 @Value("${mall.content.read-timeout-ms:1000}") long readTimeoutMs) {
        RestClient.Builder builder = baseUrl.startsWith("lb://") ? loadBalancedBuilder : RestClient.builder();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalToken = internalToken;
        log.info("内容域客户端就绪: base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    // ==================== 轮播 ====================

    public PageResult<AdminBannerVO> bannerPage(String keyword, Integer status, long pageNum, long pageSize) {
        return unwrap("bannerPage", () -> restClient.get()
                .uri(uri -> uri.path(BASE + "/banner/page")
                        .queryParamIfPresent("keyword", Optional.ofNullable(keyword))
                        .queryParamIfPresent("status", Optional.ofNullable(status))
                        .queryParam("pageNum", pageNum)
                        .queryParam("pageSize", pageSize)
                        .build())
                .header(InternalHeader.NAME, internalToken)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<PageResult<AdminBannerVO>>>() {
                }));
    }

    public Long createBanner(BannerSaveRequest request) {
        return unwrap("createBanner", () -> restClient.post()
                .uri(BASE + "/banner")
                .header(InternalHeader.NAME, internalToken)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Long>>() {
                }));
    }

    public void updateBanner(Long id, BannerSaveRequest request) {
        unwrap("updateBanner", () -> restClient.put()
                .uri(BASE + "/banner/{id}", id)
                .header(InternalHeader.NAME, internalToken)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Void>>() {
                }));
    }

    public void updateBannerStatus(Long id, Integer status) {
        unwrap("updateBannerStatus", () -> restClient.put()
                .uri(BASE + "/banner/{id}/status", id)
                .header(InternalHeader.NAME, internalToken)
                .body(Map.of("status", status))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Void>>() {
                }));
    }

    public void deleteBanner(Long id) {
        unwrap("deleteBanner", () -> restClient.delete()
                .uri(BASE + "/banner/{id}", id)
                .header(InternalHeader.NAME, internalToken)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Void>>() {
                }));
    }

    // ==================== 公告 ====================

    public PageResult<AdminNoticeVO> noticePage(String keyword, Integer status, long pageNum, long pageSize) {
        return unwrap("noticePage", () -> restClient.get()
                .uri(uri -> uri.path(BASE + "/notice/page")
                        .queryParamIfPresent("keyword", Optional.ofNullable(keyword))
                        .queryParamIfPresent("status", Optional.ofNullable(status))
                        .queryParam("pageNum", pageNum)
                        .queryParam("pageSize", pageSize)
                        .build())
                .header(InternalHeader.NAME, internalToken)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<PageResult<AdminNoticeVO>>>() {
                }));
    }

    public Long createNotice(NoticeSaveRequest request) {
        return unwrap("createNotice", () -> restClient.post()
                .uri(BASE + "/notice")
                .header(InternalHeader.NAME, internalToken)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Long>>() {
                }));
    }

    public void updateNotice(Long id, NoticeSaveRequest request) {
        unwrap("updateNotice", () -> restClient.put()
                .uri(BASE + "/notice/{id}", id)
                .header(InternalHeader.NAME, internalToken)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Void>>() {
                }));
    }

    public void updateNoticeStatus(Long id, Integer status) {
        unwrap("updateNoticeStatus", () -> restClient.put()
                .uri(BASE + "/notice/{id}/status", id)
                .header(InternalHeader.NAME, internalToken)
                .body(Map.of("status", status))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Void>>() {
                }));
    }

    public void deleteNotice(Long id) {
        unwrap("deleteNotice", () -> restClient.delete()
                .uri(BASE + "/notice/{id}", id)
                .header(InternalHeader.NAME, internalToken)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Void>>() {
                }));
    }

    // ==================== 文件上传 ====================

    /**
     * 把文件字节转发给内容域存储（MinIO 在那边）。
     *
     * <p>单体只保留"空文件 / 大小"这两个**不必过网络**的校验；
     * "是不是真图片（魔数）"由内容域判定——它才是决定"哪些字节能进公开桶"的地方，
     * 判断与存储绑在一起才不会漏。返回体 {@code {url,name,size,contentType}} 原样透传。
     */
    public Map<String, Object> upload(MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(400, "文件读取失败");
        }
        String filename = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        return unwrap("upload", () -> restClient.post()
                .uri(BASE + "/upload")
                .header(InternalHeader.NAME, internalToken)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {
                }));
    }

    // ==================== 内部 ====================

    /**
     * 统一解包：业务码非 0 → 原样抛成 {@link BusinessException}（文案、错误码都不变）；
     * 传输层异常（超时/连接失败/5xx）→ 500「系统繁忙，请稍后重试」+ 日志。
     */
    private <T> T unwrap(String action, Supplier<ApiResponse<T>> invocation) {
        ApiResponse<T> response;
        try {
            response = invocation.get();
        } catch (Exception e) {
            log.error("调用内容域失败: action={}", action, e);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response == null) {
            log.error("调用内容域返回空响应: action={}", action);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            throw new BusinessException(response.getCode(), response.getMessage());
        }
        return response.getData();
    }

    /** 内部凭据请求头名：与两个服务的 InternalApiAuthInterceptor 常量保持一致 */
    private static final class InternalHeader {
        private static final String NAME = "X-Internal-Token";

        private InternalHeader() {
        }
    }
}
