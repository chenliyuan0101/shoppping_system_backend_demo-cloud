package com.mall.admin.service;

/**
 * 管理端状态的**唯一写入口**（P7 §2.5）。
 *
 * <p>网关（{@code AdminIdentityFilter}）只读 {@code mall:cache:admin:status:{id}} 与
 * {@code mall:token:ver:admin:{id}}，把这两样写成什么由本服务负责。所有会改变管理员状态的地方
 * （未来的"禁用/启用管理员"端点、登录、定时对账）都必须走这里，不许在别处直接写 Redis ——
 * 一旦出现第二个写方，"缓存说启用、库里是禁用"这类不一致就再也说不清是谁写的。
 *
 * <h2>{@link #reconcile()} 为什么是本批的硬前置</h2>
 * 今天**没有任何"禁用管理员"的入口**（禁用 = 直接改 {@code sys_user.status}），
 * 所以"改状态时写缓存"这条路根本不会被触发。没有对账任务：
 * <pre>
 *   缓存要么不存在（网关 fail-open 放行）要么是登录时写的 status=1
 *   ⇒ `403 账号已被禁用` 永远到不了，而被禁用的管理员在路由切换后**仍然能用**
 *     （单体的 AdminAuthInterceptor 已经退出那条链路）。
 * </pre>
 */
public interface AdminStatusService {

    /**
     * 对账一轮：读 {@code sys_user.status}，把状态缓存纠正到与库一致；
     * **在"启用→禁用"跃迁时 bump `mall:token:ver:admin:{id}`**（双保险）。
     *
     * @return 本轮修正/补写的条目数（0 = 已经一致；用于日志与自检，不参与业务判断）
     */
    int reconcile();

    /**
     * 修改管理员状态（**未来"禁用管理员"端点的落点**，本批没有 HTTP 入口）。
     *
     * <p>语义（与 user-center 的会员状态写契约同构）：
     * <ol>
     *   <li>管理员不存在 → {@code 404 管理员不存在}（顺手清掉状态缓存，因为对不存在的账号没有意义）；</li>
     *   <li>状态值不是 0/1 → {@code 400 状态值仅支持 0禁用 1正常}；</li>
     *   <li>落库；</li>
     *   <li><b>提交后</b>写状态缓存（禁用写 0、启用写 1，**不是删除**——网关要靠"禁用"这个值产出 403）；</li>
     *   <li>若本次是"启用→禁用"跃迁，**提交后**再 bump 令牌版本号（把已在手里的旧令牌立刻作废）。</li>
     * </ol>
     * 第 4/5 步刻意放在 {@code afterCommit}：事务回滚时**不能**让"禁用"提前生效
     * （否则网关会给一个其实没被禁用的管理员回 403，直到 TTL 到期）。
     *
     * @throws com.mall.admin.support.BusinessException 404/400（文案见上）
     */
    void updateStatus(long adminId, Integer status);
}
