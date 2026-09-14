package com.mall.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mall-product：商品域服务（SPU / 详情 / SKU / 库存与流水 / 类目 / 品牌）。
 *
 * <p>抽它的理由不是"为了细而细"（方案 §2.2）：改造前 {@code oms.OrderServiceImpl}、
 * {@code oms.RefundServiceImpl} 直接 UPDATE {@code pms_sku.stock/sales} 并 INSERT
 * {@code pms_sku_stock_log}——交易域持有商品域的库存真值。"库存怎么扣、流水怎么写"是本域的知识，
 * 因此那 5 条现状细节（见 {@code StockCommandServiceImpl} 类注释）整体搬进本服务（方案 §4.2）。
 *
 * <p>数据库：{@code mall_product}（**白名单 6 张表**，DDL 由 {@code mysqldump --no-data} 导出改写，
 * 见 {@code db/01-mall_product-schema.sql}）。⚠️ {@code mall.pms_comment} 属**评价域**（P4 已搬去
 * {@code mall_review}），**不在**本服务的表里——迁移绝不能 {@code LIKE 'pms%'} 一把梭。
 *
 * <p><b>P6-1 批次边界（本批）</b>：
 * <ul>
 *   <li>建立服务 + 建库迁数据 + 内部接口（{@code /internal/v1/**}）+ 真库用例；</li>
 *   <li>前台 4 条读与后台 16 条写在本服务里**实现完**，但**网关路由一个字都不改**（切路由是 P6-4）；</li>
 *   <li>单体的 {@code pms} 包**不删**（P6-6）：当下真值仍在 {@code mall} 库，读写也仍走单体。</li>
 * </ul>
 *
 * <p><b>为什么这里**没有** {@code @EnableScheduling}</b>：本批没有定时任务——索引增量同步的兜底任务
 * （{@code task/ProductSearchSyncTask}）**没有搬过来**，它属于 P6-2 的 {@code mall-search}。
 * ⚠️ 这条要留着看：{@code @Scheduled} 在缺 {@code @EnableScheduling} 时**永远不会执行而且不报错**
 * （表现为"代码写了、任务不跑"的假绿）。P6-2 把任务搬过来时，必须同时把注解加上
 * （单体 {@code DemoApplication} 与 {@code MarketingApplication} 都是显式开的，口径一致）。
 */
@SpringBootApplication
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
