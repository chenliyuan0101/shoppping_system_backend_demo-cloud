package com.mall.content.client;

import com.mall.content.config.InternalApiAuthInterceptor;
import com.mall.content.support.ApiResponse;
import com.mall.content.support.dto.HomeFeedVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 商品域出站客户端：首页商品区块（类目树 + 热门 + 新品）。
 *
 * <p>只提供**一个**方法，对应商品域的 {@code GET /internal/v1/product/home-feed}——
 * 这是 P2 唯一的跨进程调用，也是后续所有"跨服务读"的样板：
 * <ol>
 *   <li>地址走服务发现（{@code lb://mall-legacy}；P6 商品域独立后改成 {@code lb://mall-product}，
 *       本类只需改一行配置）；</li>
 *   <li>带内部共享密钥 {@code X-Internal-Token}（网关不路由 {@code /internal/**}，见 §4.10）；</li>
 *   <li>**硬超时**（默认 connect 200ms / read 300ms，§2.8 非关键路径档）；</li>
 *   <li>失败一律转成 {@link ProductFeedUnavailableException}，由调用方决定降级——
 *       本类不返回 null、不吞异常（null 会让"下游挂了"和"首页本来就没商品"混在一起）。</li>
 * </ol>
 *
 * <p>配置：{@code mall.product.base-url}（默认 {@code lb://mall-product}；P8-2 起单体注册名为 mall-trade）。
 * 以 {@code http://} 开头时**绕过服务发现**直接连该地址——应急回退与本地排障用
 * （例如把 home-feed 指到固定端口的实例上，避免连带排查注册中心）。
 *
 * <p><b>超时为什么是"连接 300ms / 读 2500ms"（P2 实测校准）</b>：
 * <ul>
 *   <li>下游<b>冷启动后的第一次</b>调用实测 **1.98s**（单体刚起来时 Tomcat/连接池/JIT 全冷），
 *       随后 18~40ms；冷缓存（货架缓存刚失效）约 545ms；</li>
 *   <li>按方案 §2.8 原定的 300ms（或后来先改的 800ms）都会把**正常的冷启动请求**判成故障，
 *       结果是"每次下游发布/重启后的第一个访客看到没有商品的首页"——比慢 2 秒糟糕得多；</li>
 *   <li>代价只在"下游卡死但不拒绝连接"时才体现：首页最多等 2.5s 再降级（随后 5s 降级缓存兜住）。
 *       下游**进程不在**时是连接失败（300ms 内快速失败），不会等满读超时。</li>
 * </ul>
 * 口径写入方案 §2.9 与附录 F；P6 商品域独立部署、有了预热/多实例后重新评估。
 */
@Slf4j
@Component
public class ProductFeedClient {

    /** 商品域首页区块端点（契约形状见 {@link HomeFeedVO}） */
    private static final String FEED_PATH = "/internal/v1/product/home-feed";

    private final RestClient restClient;
    private final String internalToken;

    public ProductFeedClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                             @Value("${mall.product.base-url:lb://mall-product}") String baseUrl,
                             @Value("${mall.internal.token:}") String internalToken,
                             @Value("${mall.product.connect-timeout-ms:300}") long connectTimeoutMs,
                             @Value("${mall.product.read-timeout-ms:2500}") long readTimeoutMs) {
        // 只有 lb:// 才需要服务发现版 builder；直连地址用普通 builder，避免负载均衡器去解析一个 IP 主机名
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
     * 取首页商品区块。
     *
     * @param size 每个区块条数（商品域会夹取上限，见 {@code ProductQueryServiceImpl}）
     * @throws ProductFeedUnavailableException 下游不可用/超时/返回非 0 业务码
     */
    public HomeFeedVO homeFeed(int size) {
        try {
            ApiResponse<HomeFeedVO> response = restClient.get()
                    .uri(uri -> uri.path(FEED_PATH).queryParam("size", size).build())
                    .header(InternalApiAuthInterceptor.HEADER_INTERNAL_TOKEN, internalToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<HomeFeedVO>>() {
                    });
            if (response == null) {
                throw new ProductFeedUnavailableException("商品域返回空响应体");
            }
            if (response.getCode() != ApiResponse.SUCCESS) {
                throw new ProductFeedUnavailableException(
                        "商品域返回业务错误: code=" + response.getCode() + ", message=" + response.getMessage());
            }
            return response.getData();
        } catch (ProductFeedUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // 超时、连接被拒、5xx、反序列化失败都收敛到"商品域不可用"，由调用方降级
            throw new ProductFeedUnavailableException("调用商品域首页区块失败: " + e, e);
        }
    }
}
