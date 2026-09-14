package com.mall.marketing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * mall-marketing：营销域服务（优惠券模板 / 发放 / <b>三态核销</b>）。
 *
 * <p>抽它的理由不是"为了细而细"（方案 §2.2）：改造前 {@code oms.OrderServiceImpl} 直接
 * 读 {@code sms_coupon_member}/{@code sms_coupon} 并 CAS 写 {@code coupon_status}
 * ——交易域持有营销域的券状态机。"券怎么算、券怎么核销"是本域的知识，
 * 因此 {@code CouponRules}、5 条校验文案、抵扣封顶（{@code Math.min(discount, goodsTotal)}）
 * 整体搬进本服务（方案 §4.3）。
 *
 * <p><b>三态</b>（本服务与旧实现最大的差别，也是**修一个存量缺陷**）：
 * <pre>
 * UNUSED ──lock(orderNo)──► LOCKED ──use(orderNo)──► USED
 *    ▲                         │
 *    └────unlock(orderNo)──────┘     下单失败补偿 / 超时关单 order.closed 兜底
 * </pre>
 * 旧实现是两态（下单瞬间直接 {@code 0 → 1}），而全仓**没有任何一处把 {@code coupon_status}
 * 改回 UNUSED**——用了券的订单只要取消/超时，券就被永久烧掉（库存回了、券没回）。
 * 三态 + {@code unlock} 正是补上这半句注释（方案 §4.3.1 ①）。
 *
 * <p>数据库：{@code mall_marketing}（2 张表，DDL 由 {@code mysqldump} 导出改写，
 * 见 {@code db/01-mall_marketing-schema.sql}）。
 *
 * <p><b>为什么这里有 {@code @EnableScheduling}</b>（P5 步骤 E 新增）：本服务要跑
 * <b>每日对账</b>（{@code task.CouponLockReconcileTask}）——扫"锁太久"的券并解锁。
 * 没有这个注解，{@code @Scheduled} 方法**永远不会执行**，而且**不报错**：
 * 表现为"代码写了、任务不跑"的假绿（与"事件发了没人消费"是同一类陷阱）。
 * 单体那边也是显式开的（{@code DemoApplication} 的 {@code @EnableScheduling}），口径一致。
 */
@SpringBootApplication
@EnableScheduling
public class MarketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingApplication.class, args);
    }
}
