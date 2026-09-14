package com.mall.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 商城系统统一网关。
 *
 * <p>P1 阶段的职责只有一件：<b>把前端两个工程的流量收进来，再按路径转给后端</b>。
 * 过渡期所有路径都指向单体（服务名 {@code mall-legacy}），因此行为与改造前完全一致——
 * 这是后续所有灰度与回退的基线（回退 = 改路由，秒级生效）。
 *
 * <p>后续阶段逐步加上：JWT 预校验与身份头下传（§4.4）、限流（§3.2）、
 * 以及把已抽出的服务按路径从单体切成独立服务（§2.4 的网关路由表）。
 */
@SpringBootApplication
public class MallGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallGatewayApplication.class, args);
    }
}
