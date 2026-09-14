package com.mall.admin.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.mall.admin.support.constant.EnableStatus;
import com.mall.admin.support.dto.AdminStatusVO;

import java.time.Duration;

/**
 * 管理员状态缓存（P7 §2.5）：{@code mall:cache:admin:status:{adminId}} → {@code {adminId,username,status}}。
 *
 * <h2>它解决什么问题</h2>
 * {@code sys_user} 搬进本服务后，**网关手里没有管理员状态**（网关不查库，P7 §2），
 * 而"禁用管理员"如果不 bump 令牌版本，网关的版本校验也拦不住他
 * ⇒ 要么丢掉 `403 账号已被禁用` 这条文案（C1 漂移），要么让网关查库（违反约定）。
 * 定案：**网关读这份缓存**，本服务是它的**唯一写入方**。
 *
 * <h2>⚠️ 与会员侧（{@code MemberStatusCache}）的关键差别：禁用时"写"而不是"删"</h2>
 * 会员侧禁用是"只删不写"——因为会员的 401 文案由**下游服务查库**产出，缓存只是加速层。
 * 管理端相反：{@code 403 账号已被禁用} 由**网关**产出，而网关**只有这一份信息**。
 * 所以禁用时必须**写 {@code status=0}**：删掉键 = 网关 fail-open = 403 永远不出现。
 * <pre>
 *   启用 → putEnabled / put(status=1)      禁用 → putDisabled / put(status=0)   ← 不能删
 * </pre>
 *
 * <h2>TTL 10 分钟（照会员侧 §4.4 ② 的先例）</h2>
 * 即使某次写入丢了，脏数据也活不过 10 分钟；而"立即失效"仍由令牌版本号保证（版本不对 → 网关直接 401），
 * 两者是不同层次的保险。⚠️ 代价与补偿：TTL 到期后键消失 ⇒ 网关在那段时间里是 fail-open，
 * 但 {@link com.mall.admin.task.AdminStatusReconcileTask}（默认 10s 一轮）会在一个周期内重新写上禁用值，
 * **并且再 bump 一次版本**（见该类的类注释：这不是重复劳动，而是"缓存缺失窗口"的主动补偿）。
 *
 * <p>⚠️ fail-open：Redis 不可用时所有方法静默跳过（{@link CacheService} 的退避降级），
 * **绝不**因为写不进缓存而让登录失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminStatusCache {

    /** 与 §4.4 ② 一致：10 分钟 */
    public static final Duration TTL = Duration.ofMinutes(10);

    private final CacheService cacheService;

    /** 读缓存；未命中返回 {@code null}（Redis 不可用时也返回 null） */
    public AdminStatusVO get(long adminId) {
        return cacheService.getJson(CacheKeys.adminStatus(adminId), AdminStatusVO.class);
    }

    /**
     * 回填（登录成功 / 对账时调用）。
     *
     * @param status 0 禁用 / 1 正常（{@link com.mall.admin.support.constant.EnableStatus}）
     */
    public void put(long adminId, String username, Integer status) {
        if (status == null) {
            // 状态未知时**什么都不写**：写一份 status=null 的 JSON 会让网关正则取不到值,
            // 表现是"403 静默失效"（网关 fail-open + WARN）。宁可让键不存在（同样是 fail-open，
            // 但日志里是"键不存在"这种一眼能懂的原因）。
            log.warn("管理员状态未知，跳过状态缓存写入: adminId={}", adminId);
            return;
        }
        cacheService.setJson(CacheKeys.adminStatus(adminId),
                new AdminStatusVO(adminId, username, status), TTL);
    }

    /** 写"启用"（登录成功、重新启用时） */
    public void putEnabled(long adminId, String username) {
        put(adminId, username, EnableStatus.ENABLED);
    }

    /** 写"禁用"（**写而不是删**，见类注释） */
    public void putDisabled(long adminId, String username) {
        put(adminId, username, EnableStatus.DISABLED);
    }

    /**
     * 删除（管理员记录本身消失时调用：缓存里的“已禁用”对不存在的账号没有意义）。
     *
     * <p>⚠️ <b>禁用管理员不要用本方法</b>——那会让网关 fail-open，`403 账号已被禁用` 再也出不来。
     */
    public void evict(long adminId) {
        cacheService.delete(CacheKeys.adminStatus(adminId));
    }
}
