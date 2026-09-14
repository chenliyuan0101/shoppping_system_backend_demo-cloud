package com.mall.review;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mall-review：评价服务（商品评价的提交 / 展示 / 管理 + 待评价读模型）。
 *
 * <p>它是 P4 抽出的服务，抽它的理由不是"为了细而细"（方案 §2.2 的说明）：
 * 单体里 {@code pms → oms}（6 处）与 {@code pms → auth}（1 处）的跨域边**全部**来自评价相关代码——
 * 提交评价要读 {@code oms_order}/{@code oms_order_item} 校验归属与状态，还要 CAS 写
 * {@code oms_order_item.comment_status} 兼作并发防重；展示评价要按 {@code member_id}
 * 去 {@code ums_member} 取昵称。把评价剥出去，这些边自然消失。
 *
 * <p><b>P4 第 1 批（本批）只做骨架</b>：库（{@code mall_review}）+ 服务能启动/能注册 +
 * 一条只读切片证明"它读的是自己的库"。评价的读写业务逻辑仍在单体 pms，
 * 尚未搬到本服务；网关也还没有路由到它。这样安排的目的是让每一批都**可验证**，
 * 而不是一次搬一大坨代码却说不清哪一步坏了。
 *
 * <p>数据库：{@code mall_review}（2 张表，见 {@code db/01-mall_review-schema.sql}）。
 */
@SpringBootApplication
public class ReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewApplication.class, args);
    }
}
