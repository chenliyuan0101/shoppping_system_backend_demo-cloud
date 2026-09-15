package com.mall.trade.common.client;

import com.mall.trade.common.ApiResponse;
import com.mall.trade.common.BusinessException;
import com.mall.trade.common.PageResult;
import com.mall.trade.common.dto.AdminBannerVO;
import com.mall.trade.common.dto.AdminNoticeVO;
import com.mall.trade.common.dto.BannerSaveRequest;
import com.mall.trade.common.dto.NoticeSaveRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;
import com.mall.common.client.OutboundRestClientFactory;

/**
 * 内容域出站客户端：单体把内容域的管理端写操作与文件上传<b>转发</b>给 {@code mall-content}。
 *
 * <p><b>为什么是"转发"而不是"把接口搬过去"（P2 决策 1）</b>：这几个端点都带登录态，
 * 而校验登录态要读管理员账号表（属认证域 {@code mall-admin}）与 {@code ums_member}（属 user-center，P3）。
 * 让内容域自己校验，就等于让它直连别人的表（违背 C2）或提前落地 P3 的登录态改造。
 * 于是单体继续当"鉴权 + 对外契约"的门面，业务与数据在内容域——**这是过渡形态**，
 * 退场条件（P3 网关验签 / P7 管理端 BFF）写在方案 §5 P2 决策 1。
 *
 * <h2>HTTP 调用改由声明式接口 {@link ContentInternalApi} 承担</h2>
 * 本类保留**域语义**三件事，其余交给接口：
 * <ol>
 *   <li><b>错误透传</b>：内容域返回的 {@code code/message} 原样抛成 {@link BusinessException}，
 *       由 {@code GlobalExceptionHandler} 变成同样的响应体——客户端看不到任何差异（C1）；</li>
 *   <li><b>下游不可用 → 500</b>：写操作是"关键路径"，不能像首页那样降级成空数据；
 *       统一成既有的 {@code 500 系统繁忙，请稍后重试}，真实原因进日志；</li>
 *   <li><b>空响应按失败处理</b>：宁可 500，也不要把 {@code null} 当"没有数据"。</li>
 * </ol>
 * 内部密钥 {@code X-Internal-Token} 不再由本类手工写：{@code lb://} 走
 * {@code @LoadBalanced RestClient.Builder} 上挂的 {@code OutboundHeadersInterceptor}，
 * {@code http://} 直连由 {@link OutboundRestClientFactory} 显式挂同一个拦截器 ⇒ 两条分支的头一致。
 *
 * <p>{@link #unwrap(String, Supplier)} 的签名与全部日志文案与迁移前**逐字相同**（含 action 标签），
 * 只是里面的 {@code Supplier} 从"手写 RestClient 链"换成了"接口方法调用"。
 */
@Slf4j
@Component
public class ContentInternalClient {

    /** 下游不可用时的兜底文案：与 GlobalExceptionHandler 的 500 文案逐字一致 */
    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final ContentInternalApi api;

    public ContentInternalClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                                 OutboundRestClientFactory restClients,
                                 @Value("${mall.content.base-url:lb://mall-content}") String baseUrl,
                                 @Value("${mall.content.connect-timeout-ms:200}") long connectTimeoutMs,
                                 @Value("${mall.content.read-timeout-ms:1000}") long readTimeoutMs) {
        RestClient restClient = restClients.build(loadBalancedBuilder, baseUrl, connectTimeoutMs, readTimeoutMs);
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(ContentInternalApi.class);
        log.info("内容域客户端就绪(HTTP Interface): base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    // ==================== 轮播 ====================

    public PageResult<AdminBannerVO> bannerPage(String keyword, Integer status, long pageNum, long pageSize) {
        return unwrap("bannerPage", () -> api.bannerPage(keyword, status, pageNum, pageSize));
    }

    public Long createBanner(BannerSaveRequest request) {
        return unwrap("createBanner", () -> api.createBanner(request));
    }

    public void updateBanner(Long id, BannerSaveRequest request) {
        unwrap("updateBanner", () -> api.updateBanner(id, request));
    }

    public void updateBannerStatus(Long id, Integer status) {
        unwrap("updateBannerStatus", () -> api.updateBannerStatus(id, Map.<String, Object>of("status", status)));
    }

    public void deleteBanner(Long id) {
        unwrap("deleteBanner", () -> api.deleteBanner(id));
    }

    // ==================== 公告 ====================

    public PageResult<AdminNoticeVO> noticePage(String keyword, Integer status, long pageNum, long pageSize) {
        return unwrap("noticePage", () -> api.noticePage(keyword, status, pageNum, pageSize));
    }

    public Long createNotice(NoticeSaveRequest request) {
        return unwrap("createNotice", () -> api.createNotice(request));
    }

    public void updateNotice(Long id, NoticeSaveRequest request) {
        unwrap("updateNotice", () -> api.updateNotice(id, request));
    }

    public void updateNoticeStatus(Long id, Integer status) {
        unwrap("updateNoticeStatus", () -> api.updateNoticeStatus(id, Map.<String, Object>of("status", status)));
    }

    public void deleteNotice(Long id) {
        unwrap("deleteNotice", () -> api.deleteNotice(id));
    }

    // ==================== 文件上传 ====================

    /**
     * 把文件字节转发给内容域存储（MinIO 在那边）。
     *
     * <p>单体只保留"空文件 / 大小"这两个**不必过网络**的校验；
     * "是不是真图片（魔数）"由内容域判定——它才是决定"哪些字节能进公开桶"的地方，
     * 判断与存储绑在一起才不会漏。返回体 {@code {url,name,size,contentType}} 原样透传。
     *
     * <p>报文形状**未变**：仍是 {@code MultiValueMap} + {@code ByteArrayResource}（带 filename）
     * 交给 {@link ContentInternalApi#upload}（方法级 {@code multipart/form-data}）。
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

        return unwrap("upload", () -> api.upload(body));
    }

    // ==================== 内部 ====================

    /**
     * 统一解包：业务码非 0 → 原样抛成 {@link BusinessException}（文案、错误码都不变）；
     * 传输层异常（超时/连接失败/5xx）→ 500「系统繁忙，请稍后重试」+ 日志。
     *
     * <p>为什么迁移到声明式接口后**仍然**要这层 try/catch：接口解决的是"HTTP ↔ 类型"的样板，
     * **不解决错误语义**——连接被拒/超时抛 {@code ResourceAccessException}，4xx/5xx 抛
     * {@code HttpClientErrorException}（RestClient 默认状态处理器），两者都要在这里变成"域的失败"。
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
}
