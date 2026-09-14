package com.mall.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * mall-search：检索域服务（Elasticsearch 索引维护 + 商品检索）。
 *
 * <p>抽它的理由（方案 §2.2/§4.2）：改造前 {@code pms.ProductSearchServiceImpl} 与
 * {@code pms.mq.ProductSync*} 把"ES 索引怎么建、文档怎么写、失败怎么重试"这套知识
 * 放在商品域里，而 ES 是**独立存储**（不是商品表的从属视图）——它有自己的生命周期、
 * 自己的失败模式、自己的重建流程。方案 §5 P6 因此把"检索"单独拆成一个服务。
 *
 * <p><b>本服务没有自己的 MySQL 表</b>（规格 §1）：索引文档的内容一律**向 mall-product 拉**
 * （{@code POST /internal/v1/product/index-docs}），绝不"读旧库、写新索引"。
 *
 * <p><b>为什么这里有 {@code @EnableScheduling}</b>：本服务要跑
 * {@code task/ProductSearchSyncTask}——消费 {@code mall:es:pending}（MQ 不可用时的兜底通道）。
 * 没有这个注解，{@code @Scheduled} 方法**永远不会执行，而且不报错**：
 * 表现是"代码写了、任务不跑"的假绿（P6-1 的 class 注释里专门留了这条提醒，这里兑现）。
 * 单体 {@code DemoApplication} 与 {@code MarketingApplication} 也都是显式开的，口径一致。
 *
 * <p><b>P6-2 批次边界</b>：建服务 + 搬检索/索引维护/消息通道 + 真 ES 用例；
 * **不切任何路由**（P6-4）、**不动单体的 ES 代码**（"发布方搬去 product"属 P6-5）、
 * 索引名保持 {@code mall_product}、**不引入别名**。
 */
@SpringBootApplication
@EnableScheduling
public class SearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
