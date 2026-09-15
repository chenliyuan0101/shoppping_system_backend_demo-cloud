package com.mall.gateway.config;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 把 {@code /internal/**} 挡在网关之外：<b>内部接口不是对外 API 的一部分</b>。
 *
 * <p>背景：P1 的路由是 {@code Path=/**}（全部路径 → 单体），如果不拦，
 * {@code /internal/**} 会跟着被转发出去——那等于把"能扣库存、能核销券、能改会员状态"
 * 的接口挂到外网上。本过滤器在任何路由匹配之前直接返回 <b>404</b>。
 *
 * <p>为什么返回 404 而不是 403：<b>不暴露"这里存在什么"</b>。403 等于告诉探测者
 * "路径存在但你没权限"，404 连存在性都不确认。响应体留空也是有意的——
 * 网关不依赖 {@code mall-common}，这里也不该为了凑格式把内部服务的响应体约定搬过来。
 *
 * <p>与单体侧的 {@code InternalApiAuthInterceptor}（校验 {@code X-Internal-Token}）构成两道防线：
 * 网关挡外网路径，密钥挡"能直连服务端口"的情况（内网横向、误暴露调试端口）。
 */
@Component
public class InternalPathBlockFilter implements GlobalFilter, Ordered {

    /** 内部接口前缀（与单体侧的拦截器路径保持一致） */
    private static final String INTERNAL_PREFIX = "/internal/";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith(INTERNAL_PREFIX)) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // 最高优先级：先于路由匹配执行，避免"先转发再判断"的窗口
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
