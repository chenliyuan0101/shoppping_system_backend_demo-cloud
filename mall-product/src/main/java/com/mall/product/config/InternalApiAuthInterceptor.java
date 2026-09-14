package com.mall.product.config;

import com.mall.product.support.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口鉴权：拦住 {@code /internal/**}（服务间调用专用，不是给用户/前端用的）。
 *
 * <p>逐字沿用 mall-user-center / mall-content / mall-review / mall-marketing 的同一份实现
 * （只换包名）——身份模型与内部鉴权必须是**全站同一套**，任何服务自作主张改一点，
 * 就会出现"某个服务能被冒充"的静默漏洞。
 *
 * <p><b>为什么必须有这道闸</b>：本服务的 {@code /internal/v1/stock/**} 里有
 * "预占库存""回补库存""累加销量"这类**改商品真值**的能力，而且请求体里的
 * {@code orderNo}/{@code skuId} 是调用方（trade）在服务端上下文里传进来的——一旦暴露而没有鉴权，
 * 任何人构造一个 POST 就能：① 把任意 SKU 的库存扣到 0（超卖/断货）；② 凭空回补库存；
 * ③ 刷高任意商品销量（前台按销量排序立刻被污染）。
 *
 * <p>两道防线（缺一不可）：
 * <ol>
 *   <li><b>网关不路由 {@code /internal/**}</b>：{@code mall-gateway} 里的过滤器直接 404，
 *       外网经网关永远看不到这些路径；</li>
 *   <li><b>本拦截器校验共享密钥</b>：即便有人能直连服务端口（内网横向、误暴露、调试端口），
 *       也必须带上正确的 {@code X-Internal-Token} 才能调用。</li>
 * </ol>
 *
 * <p>⚠️ 当前实现是<b>静态共享密钥</b>（常量时间比较，避免时序侧信道）。方案 §4.10 规划的是
 * "HMAC + 时间戳防重放"——P6-4 起 trade 会真的调过来，届时再升级。
 *
 * <p>配置项：{@code mall.internal.token}。未配置时：<b>拒绝所有内部调用</b>（fail-closed）——
 * ⚠️ 本服务**全部对外能力都在 {@code /internal/**}**（P6-1 不切流量），所以漏配的表现是
 * "trade 一下单就报内部接口鉴权失败"，而不是"悄悄对外开放了扣库存能力"。
 */
@Component
public class InternalApiAuthInterceptor implements HandlerInterceptor {

    /** 内部调用凭据的请求头名 */
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    private final byte[] expectedToken;

    public InternalApiAuthInterceptor(@Value("${mall.internal.token:}") String token) {
        this.expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // fail-closed：没配置密钥就一律拒绝（避免"忘了配置"变成"完全开放"）
        if (expectedToken.length == 0) {
            throw new BusinessException(403, "内部接口未配置访问凭据");
        }
        String provided = request.getHeader(HEADER_INTERNAL_TOKEN);
        if (!StringUtils.hasText(provided) || !constantTimeEquals(provided)) {
            throw new BusinessException(403, "内部接口鉴权失败");
        }
        return true;
    }

    /** 常量时间比较：避免按字节提前返回而泄漏"前缀猜对了几个字符" */
    private boolean constantTimeEquals(String provided) {
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expectedToken);
    }
}
