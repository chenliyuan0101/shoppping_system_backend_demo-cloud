package com.mall.product.config;

import com.mall.product.support.AuthAttribute;
import com.mall.product.support.AuthHeader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端接口鉴权拦截器（P6-1b）：拦截 {@code /api/admin/**}。
 *
 * <h2>本服务真正校验了什么（就这三项）</h2>
 * <ol>
 *   <li>头缺失/不是 {@code Bearer xxx} → 401「未登录」（{@link AuthHeader#bearer}）；</li>
 *   <li>HS256 签名 + 有效期（{@code exp}），结构/签名/过期错 → 401「登录已失效，请重新登录」；</li>
 *   <li>{@code typ} 必须是 {@code admin} → 否则 401「登录已失效，请使用管理员账号登录」
 *       （双体系隔离：会员令牌不得用于后台接口）。</li>
 * </ol>
 * 通过后把管理员 id 写进请求属性 {@link AuthAttribute#ADMIN_USER_ID}（属性名取自共享内核，
 * 避免别的域为了读它而 import 本类 —— 单体那边就是踩过这个跨域依赖）。
 *
 * <h2>⚠️ 本服务**没有**校验什么（这一节不许删、不许改写成"已完整鉴权"）</h2>
 * 单体那条链路上还有三项校验，本服务**做不了**，仍由**单体保留、P7 随认证域一起移交
 * {@code mall-admin} BFF（或网关）**：
 * <ol>
 *   <li><b>管理员是否存在</b>（{@code sys_user} 查不到 → 401「登录已失效，请重新登录」）：
 *       要读 {@code sys_user} 表，那是认证域的数据（C2 边界：商品域不得读别人的库）；</li>
 *   <li><b>账号是否被禁用</b>（{@code status != 1} → 403「账号已被禁用」）：同上，要读 {@code sys_user}；</li>
 *   <li><b>令牌版本号</b>（{@code ver} 与认证域当前版本一致，登出/改密/禁用后旧令牌立即失效）：
 *       版本号存在认证域的 Redis/库里，本服务读不到，因此 {@code ver} 只是**读出来不校验**。</li>
 * </ol>
 * ⇒ 所以本拦截器**比"匿名可调"严格得多，但不是完整的鉴权**：
 * 一个"签名正确、没过期、typ=admin，但其账号已被禁用/已登出"的令牌，在 P7 之前仍然能通过这里。
 * 这一段就是 P6-4 切路由前必须由主 agent 拍板的残留风险（我已在汇报里点名）。
 *
 * <h2>排除路径：一个都没有</h2>
 * 本服务**没有** {@code /api/admin/auth/**}（登录端点在单体/P7 的 admin BFF）。
 * 若在这里"排除"一条不存在的路径，读者会以为本服务能登录 —— 见 {@code WebConfig} 的注释。
 */
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final JwtVerifier jwtVerifier;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = AuthHeader.bearer(request.getHeader("Authorization"));
        JwtVerifier.Claims claims = jwtVerifier.parse(token);
        // 双体系隔离：会员令牌不得用于后台接口（即使 sub 恰好与某个管理员 id 相同）
        if (!JwtVerifier.TYPE_ADMIN.equals(claims.type())) {
            throw new com.mall.product.support.BusinessException(401, "登录已失效，请使用管理员账号登录");
        }
        request.setAttribute(AuthAttribute.ADMIN_USER_ID, claims.userId());
        return true;
    }
}
