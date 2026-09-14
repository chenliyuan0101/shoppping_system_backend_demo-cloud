package com.mall.admin.service.impl;

import com.mall.admin.domain.AdminUser;
import com.mall.admin.mapper.AdminUserMapper;
import com.mall.admin.service.AdminStatusService;
import com.mall.admin.support.AdminStatusCache;
import com.mall.admin.support.BusinessException;
import com.mall.admin.support.TokenVersionService;
import com.mall.admin.support.TxCallbacks;
import com.mall.admin.support.constant.EnableStatus;
import com.mall.admin.support.dto.AdminStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 管理端状态写入方实现（P7 §2.5）。
 *
 * <h2>对账（{@link #reconcile()}）的判定规则</h2>
 * <pre>
 * 对每一行 sys_user：
 *   缓存里的 status 与库里的 status 一致  → 什么都不做（收敛）
 *   库里 = 1（启用）                      → 写 status=1（不 bump）
 *   库里 = 0（禁用）                      → 写 status=0  **并且** bump 令牌版本
 * </pre>
 *
 * <h3>为什么"禁用"这一支要 bump，而且"每次补写都 bump"也是对的</h3>
 * <ul>
 *   <li>bump 是**第二道保险**：状态缓存是网关的判据，但它可能被清、可能被写错、TTL 也有 10 分钟；
 *       版本号一旦 bump，被禁用管理员手里的旧令牌在网关就过不去（文案 {@code 401 登录已失效，请重新登录}）。
 *       两道一起上，才不会出现"缓存丢了 ⇒ 禁用失效"。</li>
 *   <li>缓存条目（TTL 10 分钟）到期后会重新变成"键不存在"，而**键不存在时网关是 fail-open 的**
 *       ⇒ 下一轮对账会重新写上禁用值，并**再 bump 一次**。
 *       这不是重复劳动，而是"缓存缺失窗口"的主动补偿：宁可多一次 INCR，也不留一个"网关放行"的窗口。
 *       代价可忽略（每个被禁用的管理员每 10 分钟一次 INCR；被禁用的管理员无法登录，版本号不会影响任何人）。
 *       ⚠️ 状态已写入后（未到期）的每一轮都**不会**再 bump —— 收敛性由"缓存值 = 库值"这一条保证
 *       （用例 {@code reconcile_isConvergent_secondRunDoesNotBumpAgain} 把它钉住）。</li>
 * </ul>
 *
 * <h3>写缓存放在 afterCommit</h3>
 * {@link #updateStatus} 的缓存写入与版本 bump 都注册在事务提交之后（{@link TxCallbacks}）：
 * 事务回滚了就不该让外部世界看见"禁用"（P7 §5 第 8 条 / P6-4 的 D1 教训）。
 * 对账任务本身不在事务里（只读一行数据 + 写 Redis），因此是立即执行。
 *
 * <h3>fail-open</h3>
 * Redis 不可用时 {@link AdminStatusCache}/{@link TokenVersionService} 全部静默降级
 * （{@code CacheService} 的 30s 退避 + {@code log.warn}）——
 * **写不进缓存绝不能让登录/改状态失败**；那一层由下一轮对账补上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminStatusServiceImpl implements AdminStatusService {

    private final AdminUserMapper adminUserMapper;
    private final AdminStatusCache adminStatusCache;
    private final TokenVersionService tokenVersionService;

    @Override
    public int reconcile() {
        List<AdminUser> admins = adminUserMapper.selectList(null);
        int fixed = 0;
        for (AdminUser admin : admins) {
            if (admin == null || admin.getId() == null) {
                continue;
            }
            AdminStatusVO cached = adminStatusCache.get(admin.getId());
            if (cached != null && Objects.equals(cached.status(), admin.getStatus())) {
                continue;   // 已一致：不动 Redis（收敛 + 不打无谓的 INCR）
            }
            boolean enabled = admin.getStatus() != null && admin.getStatus() == EnableStatus.ENABLED;
            if (enabled) {
                adminStatusCache.putEnabled(admin.getId(), admin.getUsername());
                log.info("管理员状态缓存对账：补写启用状态 adminId={} username={}", admin.getId(), admin.getUsername());
            } else {
                // 禁用：写 0（**不能删**：网关读到"禁用"才会给 403）+ bump 版本（双保险）
                adminStatusCache.putDisabled(admin.getId(), admin.getUsername());
                long ver = tokenVersionService.bump(TokenVersionService.TYPE_ADMIN, admin.getId());
                log.warn("管理员状态缓存对账：发现禁用（启用→禁用或缓存缺失），已写禁用值并提升令牌版本号"
                                + " adminId={} username={} 库status={} 缓存status={} 新ver={}",
                        admin.getId(), admin.getUsername(), admin.getStatus(),
                        cached == null ? "<无缓存>" : cached.status(), ver);
            }
            fixed++;
        }
        return fixed;
    }

    @Override
    @Transactional
    public void updateStatus(long adminId, Integer status) {
        AdminUser admin = adminUserMapper.selectById(adminId);
        if (admin == null) {
            // 账号都不存在了，"禁用"这份缓存留着只会误导（网关据此回 403 而不是"重新登录"）
            adminStatusCache.evict(adminId);
            throw new BusinessException(404, "管理员不存在");
        }
        if (status == null || (status != EnableStatus.DISABLED && status != EnableStatus.ENABLED)) {
            throw new BusinessException(400, "状态值仅支持 0禁用 1正常");
        }
        AdminUser update = new AdminUser();
        update.setId(adminId);
        update.setStatus(status);
        adminUserMapper.updateById(update);

        boolean enabledToDisabled = status == EnableStatus.DISABLED
                && (admin.getStatus() == null || admin.getStatus() == EnableStatus.ENABLED);
        String username = admin.getUsername();

        // 提交后才让外部世界看见（回滚 ⇒ 什么都不写、什么都不 bump）
        TxCallbacks.afterCommitOrNow(() -> {
            if (status == EnableStatus.DISABLED) {
                // 写而不是删：网关要靠这个值产出「403 账号已被禁用」
                adminStatusCache.putDisabled(adminId, username);
            } else {
                adminStatusCache.putEnabled(adminId, username);
            }
            if (enabledToDisabled) {
                // 双保险：缓存被清/写错/TTL 到期都不影响"旧令牌立刻不可用"
                long ver = tokenVersionService.bump(TokenVersionService.TYPE_ADMIN, adminId);
                log.warn("管理员被禁用，已写状态缓存并提升令牌版本号: adminId={} username={} 新ver={}",
                        adminId, username, ver);
            }
        });
    }
}
