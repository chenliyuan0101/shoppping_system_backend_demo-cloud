package com.mall.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * mall-admin：管理端 BFF（P7）。
 *
 * <h2>本服务是什么（P7 §1 的归属划分）</h2>
 * <ul>
 *   <li>{@code /api/admin/auth/**}（login/me/logout）——本批交付；</li>
 *   <li>后续（P7 后半）：{@code /api/admin/dashboard/**} 看板并行聚合、{@code /api/admin/member/**} 会员管理。</li>
 * </ul>
 * {@code /api/admin/{product,category,brand}/**} 归 product、{@code coupon} 归 marketing、
 * {@code stat|mq} 归 trade、{@code es} 归 search —— <b>都不经过本服务</b>。
 *
 * <h2>它同时是"管理端两个 Redis key 的唯一写入方"（P7 §2.5，本批最重要的交付）</h2>
 * 网关（{@code AdminIdentityFilter}）**只读** {@code mall:cache:admin:status:{id}} 与
 * {@code mall:token:ver:admin:{id}}；把它们写成什么，是本服务的责任：
 * <pre>
 *   · 登录成功          → 写 status=1（启用）
 *   · 管理员被禁用      → 写 status=0（**写而不是删**：网关要靠它产出「403 账号已被禁用」）
 *                        + bump 令牌版本（双保险：缓存被清也拦得住）
 *   · 定时对账（~10s）  → 读 sys_user.status 回填/纠正缓存，并在"启用→禁用"跃迁时 bump 版本
 * </pre>
 * ⚠️ 为什么必须有对账任务：**今天没有任何"禁用管理员"的入口**（禁用是直接改 {@code sys_user}），
 * 所以只靠"改状态时写缓存"这条路，缓存永远停在登录时写的 {@code status=1} ⇒
 * 一旦路由切到本服务、单体的 {@code AdminAuthInterceptor} 退出链路，被禁用的管理员**还能用**。
 *
 * <p>数据库：{@code mall_admin}（**只有 {@code sys_user} 一张表**，见 {@code db/01-mall_admin-schema.sql}）。
 * 其它域的表一张都不搬 —— 管理端 BFF 不持有别人的数据。
 *
 * <p><b>为什么这里有 {@code @EnableScheduling}</b>：本服务要跑
 * {@link com.mall.admin.task.AdminStatusReconcileTask}（状态缓存对账）。
 * 少了这个注解，{@code @Scheduled} 方法**永远不会执行，而且不报错**——
 * 表现是"缓存里的禁用状态一直不更新"，属于最难查的一类静默失效（search/marketing 都为此留过注释）。
 */
@SpringBootApplication
@EnableScheduling
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
