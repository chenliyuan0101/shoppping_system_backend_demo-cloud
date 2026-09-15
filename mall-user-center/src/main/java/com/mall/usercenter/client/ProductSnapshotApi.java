package com.mall.usercenter.client;

import com.mall.usercenter.support.ApiResponse;
import com.mall.usercenter.support.dto.SkuSnapshotVO;
import com.mall.usercenter.support.dto.SpuSnapshotVO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.Map;

/**
 * <b>商品域"购物车/收藏快照"内部契约的声明式接口</b>（Spring HTTP Interface，替换原先手写的 {@code RestClient} 调用）。
 *
 * <h2>为什么这层能有收益（而不是"为了新写法而新写法"）</h2>
 * <ol>
 *   <li><b>泛型不再靠人工传 {@code ParameterizedTypeReference}</b>：原先三个方法各写一遍匿名子类
 *       （{@code new ParameterizedTypeReference<ApiResponse<List<SkuSnapshotVO>>>(){}}、
 *       {@code …<ApiResponse<LinkedHashMap<Long, Long>>>(){}}），泛型方法里的 {@code T} 一旦被擦除，
 *       Jackson 只会把 {@code data} 反序列化成 {@code LinkedHashMap}，调用方随后 {@code ClassCastException}，
 *       而**编译期完全看不出来**。声明式接口的返回类型是**方法签名的一部分**，
 *       框架从 {@code MethodParameter} 解析泛型 ⇒ 这个坑结构性消失（"字符串 key → Long" 也由声明的
 *       {@code Map<Long, Long>} 直接承担，不再依赖 {@code LinkedHashMap} 的偶然形状）。</li>
 *   <li><b>URL 只写一次</b>：三个端点路径集中在 {@code @PostExchange} 上，客户端类里不再有
 *       {@code SKU_BATCH_PATH}/{@code SPU_BATCH_PATH}/{@code SKU_MIN_PRICE_PATH} 三个常量。</li>
 *   <li><b>出站头不再手工写</b>：{@code X-Internal-Token} / {@code X-Trace-Id} 由挂在
 *       {@code @LoadBalanced RestClient.Builder} 上（<b>直连分支由 {@code OutboundRestClientFactory}
 *       显式挂上</b>）的 {@code OutboundHeadersInterceptor} 统一注入，
 *       接口方法上不需要（也不应该）再写 {@code .header(HEADER_INTERNAL_TOKEN, …)}——
 *       这正是"直连排障模式也带内部令牌"的落点（见 {@code ProductSnapshotClientTest} 的断言）。</li>
 * </ol>
 *
 * <h2>这一层**不做**什么（刻意留给 {@link ProductSnapshotClient}）</h2>
 * <ul>
 *   <li><b>不处理错误语义</b>：传输异常 / 空响应 / 业务码非 0 → {@code ProductUnavailableException}
 *       （最低价那条再降级为空 Map），都在客户端类里；</li>
 *   <li><b>不做形状适配</b>："去重 + 空集合不发请求"留在客户端类里；</li>
 *   <li><b>不做降级</b>：降级是不对称的域策略（起售价降级、快照不降级），只有客户端类知道哪条是哪条。</li>
 * </ul>
 *
 * <p>⚠️ 返回类型是**下游的原始响应体** {@code ApiResponse<T>}（不是 {@code T}）：解包与文案映射统一在
 * 客户端类的 {@code exchange} 里做，接口只负责"HTTP ↔ 类型"这一步。
 *
 * <p>⚠️ 路径前缀 {@code /internal/v1/product}：网关有过滤器直接 404 {@code /internal/**}（外网永远看不到）。
 */
@HttpExchange(url = "/internal/v1/product", contentType = "application/json")
public interface ProductSnapshotApi {

    /**
     * 批量 SKU 快照（购物车列表/加购/改数量所需的"现价、库存、上下架、规格"）。
     *
     * <p>请求体 {@code {"ids":[…skuId…]}}：单条查询也走批量端点（一种请求形状、一个超时点），
     * "去重 + 空集合不发请求"由客户端类负责。不存在的 id 不会出现在结果里。
     */
    @PostExchange("/sku/batch")
    ApiResponse<List<SkuSnapshotVO>> skus(@RequestBody Map<String, Object> body);

    /** 批量 SPU 快照（购物车/收藏条目要的标题、主图）；请求体同为 {@code {"ids":[…spuId…]}} */
    @PostExchange("/spu/batch")
    ApiResponse<List<SpuSnapshotVO>> spus(@RequestBody Map<String, Object> body);

    /**
     * 批量取"每个 SPU 下启用 SKU 的最低价"（收藏/足迹列表的起售价）。
     *
     * <p>⚠️ 请求体同样是 {@code {"ids":[…]}}，但传的是 <b>SPU</b> id（不是 skuId）；
     * 响应 {@code Map<Long,Long>}（JSON 对象的 key 是**字符串**形式的 spuId，由声明的 {@code Long} key 类型转换）；
     * 口径：只看 {@code status=1} 的 SKU、忽略价格为空的，没有可用 SKU 的 SPU 不出现在结果里。
     */
    @PostExchange("/sku/min-price/batch")
    ApiResponse<Map<Long, Long>> minEnabledSkuPrices(@RequestBody Map<String, Object> body);
}
