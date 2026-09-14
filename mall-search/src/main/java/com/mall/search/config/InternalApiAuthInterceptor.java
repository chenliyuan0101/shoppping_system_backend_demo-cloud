package com.mall.search.config;

import com.mall.search.support.BusinessException;
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
 * <p>逐字沿用 mall-user-center / mall-content / mall-review / mall-marketing / mall-product 的同一份实现
 * （只换包名）——身份模型与内部鉴权必须是**全站同一套**。
 *
 * <p><b>为什么必须有这道闸</b>：本服务的 {@code /internal/v1/search/**} 里有
 * "重建索引""从索引删除""按品牌全量重写"这类**改检索真值**的能力。一旦没有鉴权，
 * 任何人构造一个 POST 就能：① 把整个检索索引删掉重建（重建期间检索不可用）；
 * ② 把任意商品从索引里删掉（用户搜不到，而库里还在——最难查的一类"数据不见了"）。
 *
 * <p>两道防线（缺一不可）：
 * <ol>
 *   <li><b>网关不路由 {@code /internal/**}</b>：{@code mall-gateway} 里的过滤器直接 404；</li>
 *   <li><b>本拦截器校验共享密钥</b>：即便有人能直连 8103，也必须带正确的 {@code X-Internal-Token}。</li>
 * </ol>
 *
 * <p>配置项 {@code mall.internal.token}。未配置时**拒绝所有内部调用**（fail-closed）——
 * ⚠️ 本服务**全部对外能力都在 /internal/**，所以漏配的表现是"调用方一律 403"，
 * 而不是"悄悄对外开放了删索引能力"。
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
