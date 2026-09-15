package com.mall.content.client;

import com.mall.content.config.OutboundRestClientFactory;
import com.mall.content.support.ApiResponse;
import com.mall.content.support.dto.HomeFeedVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * 商品域出站客户端：首页商品区块（类目树 + 热门 + 新品）。
 *
 * <p>只提供**一个**方法，对应商品域的 {@code GET /internal/v1/product/home-feed}——
 * 这是 P2 唯一的跨进程调用，也是后续所有"跨服务读"的样板：
 * <ol>
 *   <li>地址走服务发现（{@code lb://mall-legacy}；P6 商品域独立后改成 {@code lb://mall-product}，
 *       本类只需改一行配置）；</li>
 *   <li>带内部共享密钥 {@code X-Internal-Token}（网关不路由 {@code /internal/**}，见 §4.10）；
 *       P8-7 起这个头**不再手工写**，由 {@code OutboundHeadersInterceptor} 统一注入；</li>
 *   <li>**硬超时**（默认 connect 200ms / read 300ms，§2.8 非关键路径档）；</li>
 *   <li>失败一律转成 {@link ProductFeedUnavailableException}，由调用方决定降级——
 *       本类不返回 null、不吞异常（null 会让"下游挂了"和"首页本来就没商品"混在一起）。</li>
 * </ol>
 *
 * <h2>P8-7 起：HTTP 调用改由声明式接口 {@link ProductFeedApi} 承担</h2>
 * 本类保留**域语义**三件事，其余交给接口：
 * <ul>
 *   <li><b>连接装配</b>：{@code lb://} 走服务发现（{@code @LoadBalanced} 的 builder，其上已挂
 *       {@code OutboundHeadersInterceptor}）、{@code http://} 直连（排障/演练用，由
 *       {@link OutboundRestClientFactory} 显式挂同一个拦截器），并设置 connect/read 超时；</li>
 *   <li><b>错误语义</b>：传输异常 / 空响应 / 业务码非 0 → {@link ProductFeedUnavailableException}
 *       （与改造前逐字相同的文案），调用方据此 fail-open；</li>
 *   <li><b>数据形状</b>：解包 {@code ApiResponse<HomeFeedVO>} → {@code HomeFeedVO}。</li>
 * </ul>
 *
 * <h2>为什么保留这个类（而不是让调用方直接用接口）</h2>
 * ① 上面那几件事仍需要一个落点；② 调用方（{@code HomeServiceImpl}）与真库套件的
 * {@code @MockitoBean ProductFeedClient} 都对着**这个类**，保留它 ⇒ 服务层与测试一行都不用改。
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
 *
 * <p>⚠️ 路径前缀 {@code /internal/**}：网关有过滤器直接 404 这一前缀（外网永远看不到）。
 */
@Slf4j
@Component
public class ProductFeedClient {

    private final ProductFeedApi api;

    public ProductFeedClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                             OutboundRestClientFactory restClients,
                             @Value("${mall.product.base-url:lb://mall-product}") String baseUrl,
                             @Value("${mall.product.connect-timeout-ms:300}") long connectTimeoutMs,
                             @Value("${mall.product.read-timeout-ms:2500}") long readTimeoutMs) {
        RestClient restClient = restClients.build(loadBalancedBuilder, baseUrl, connectTimeoutMs, readTimeoutMs);
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(ProductFeedApi.class);
        log.info("商品域客户端就绪(HTTP Interface): base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 取首页商品区块。
     *
     * @param size 每个区块条数（商品域会夹取上限，见 {@code ProductQueryServiceImpl}）
     * @throws ProductFeedUnavailableException 下游不可用/超时/空响应/返回非 0 业务码
     */
    public HomeFeedVO homeFeed(int size) {
        ApiResponse<HomeFeedVO> response;
        try {
            response = api.homeFeed(size);
        } catch (Exception e) {
            // 超时、连接被拒、5xx、反序列化失败都收敛到"商品域不可用"，由调用方降级
            throw new ProductFeedUnavailableException("调用商品域首页区块失败: " + e, e);
        }
        if (response == null) {
            throw new ProductFeedUnavailableException("商品域返回空响应体");
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            throw new ProductFeedUnavailableException(
                    "商品域返回业务错误: code=" + response.getCode() + ", message=" + response.getMessage());
        }
        return response.getData();
    }
}
