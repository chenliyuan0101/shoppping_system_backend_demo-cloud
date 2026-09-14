package com.mall.content;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mall-content：内容域服务（Banner / 公告 / 首页聚合 / 文件上传）。
 *
 * <p>它是 P2 抽出的第一个业务服务，因此这个工程同时是**后续 6 个服务的模板**：
 * <ul>
 *   <li>自带组装根（{@code com.mall.content} 下的 {@code config} 包），不依赖单体任何代码；</li>
 *   <li>自带共享内核副本（{@code support} 包）与跨服务契约快照（{@code support.dto}）；</li>
 *   <li>自带数据源（{@code mall_content}）——**一个服务一个库**，P2 决策 2；</li>
 *   <li>对外只暴露两类接口：公开读路径（网关路由过来）与 {@code /internal/**}（服务间调用，需共享密钥）。</li>
 * </ul>
 */
@SpringBootApplication
public class ContentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContentApplication.class, args);
    }
}
