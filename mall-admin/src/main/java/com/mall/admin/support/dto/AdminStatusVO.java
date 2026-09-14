package com.mall.admin.support.dto;

/**
 * 管理员状态快照（P7 §2.5 的 Redis 载荷；与会员侧 {@code MemberStatusVO} 同构，只是字段换成 admin）。
 *
 * <p><b>这是跨服务契约</b>：写入方**只有**本服务（登录 / 改状态 / 定时对账），
 * 读取方是网关 {@code AdminIdentityFilter}。网关接受两种写法：
 * <pre>
 *   ① {"adminId":9,"username":"admin","status":0}   ← 本类序列化出来的形状（推荐）
 *   ② 裸数字 "0" / "1"
 * </pre>
 * 网关从值里用正则取 {@code "status"} 字段；取不到就 **fail-open 放行**（只留 WARN）
 * ⇒ 字段名写错等于"403 检查静默失效"。因此：
 * <ul>
 *   <li>字段名 {@code adminId}/{@code username}/{@code status} **不许改**（改 = 改跨服务契约）；</li>
 *   <li>{@code status} 用包装类型 {@link Integer}：{@code null} 表示"状态未知"，
 *       不许把它当成"启用"或"禁用"来写缓存（本服务的写入方永远写 0 或 1）。</li>
 * </ul>
 *
 * <p>刻意做**窄**：不含密码（BCrypt 密文）、create_time 等无关字段 ——
 * 缓存是会到处传播的数据，放什么进去等于把它发到所有读它的进程里。
 *
 * @param adminId  管理员 id
 * @param username 登录名（运维排查用：一眼看出"这条缓存是谁的"）
 * @param status   状态：0 禁用 / 1 正常（{@link com.mall.admin.support.constant.EnableStatus}）
 */
public record AdminStatusVO(Long adminId, String username, Integer status) {
}
