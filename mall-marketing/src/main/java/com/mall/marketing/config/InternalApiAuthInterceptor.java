package com.mall.marketing.config;

import com.mall.marketing.support.BusinessException;
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
 * <p><b>为什么必须有这道闸</b>：本服务的 {@code /internal/v1/marketing/coupon/**} 里有
 * "锁定券""核销券"这类**改钱**的能力，而且请求体里的 {@code memberId} 是调用方（trade）
 * 在服务端上下文里传进来的——一旦暴露而没有鉴权，任何人构造一个 POST 就能：
 * ① 锁死别人的券（可用券变成锁定中）；② 把别人锁定的券核销掉；③ 探知任意会员的可用券。
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
 * "HMAC + 时间戳防重放"——等真正有跨进程调用的阶段再升级（批次 2 起 trade 会真的调过来）。
 *
 * <p>配置项：{@code mall.internal.token}。未配置时：<b>拒绝所有内部调用</b>（fail-closed）——
 * 宁可内部调用失败，也不能默认放行。
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
