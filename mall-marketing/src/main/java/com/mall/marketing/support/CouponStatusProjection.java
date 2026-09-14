package com.mall.marketing.support;

import com.mall.marketing.support.constant.CouponMemberStatus;

import java.util.List;

/**
 * <b>券状态的内外词表投影（C1 硬约束，方案 §4.3.1 ②③）。</b>
 *
 * <p>库里的值域在 P5 变成了 {@code 0/1/2/3}，但**对外词表仍然是 {@code 0/1/2}**：
 * 前端两个工程已经把它钉死（{@code CouponCenter.vue} L90-91、{@code CouponList.vue} L82），
 * 而且旧实现里"下单瞬间券就已 USED"，所以**锁定窗口内的券对外必须仍然显示"已使用"**——
 * 否则用户会第一次看到"未使用却不能用"的券。
 *
 * <table border="1">
 *   <caption>投影规则</caption>
 *   <tr><th>对外字段</th><th>规则</th></tr>
 *   <tr><td>{@code couponStatus}（{@code /api/coupon/mine}、{@code /api/admin/coupon/{id}/records}）</td>
 *       <td>库里 {@code 3} → 对外 {@code 1}（见 {@link #toExternal(Integer)}）</td></tr>
 *   <tr><td>{@code ?status=} 过滤（mine 的 status 参数）</td>
 *       <td>{@code 0} → 只筛 {@code 0}；{@code 1} → {@code IN (1,3)}；{@code 2} → 只筛 {@code 2}
 *           （见 {@link #dbStatusFilter(Integer)}）</td></tr>
 *   <tr><td>可用券列表（{@code usable}）</td>
 *       <td>只筛 {@code 0}（不变）——所以锁定中的券不会出现在可用券里</td></tr>
 * </table>
 *
 * <p>⚠️ 这两个方法是**纯函数**，本批（P5-1）还没有任何公开端点调用它们
 * （对外端点属批次 2/3），所以它们现在只有单测证明——但必须**先写好**：
 * 对外端点是"读"路径，读路径一旦上线，投影漏一处就会出现"同一张券在 A 页面已使用、
 * 在 B 页面未使用"，而那时再补会同时改动两处查询。
 *
 * <p>⚠️ 本批的 {@code /internal/**} 端点**不做投影**：trade 拿到的是库里的真值
 * （{@code 0/1/2/3}）；它只在本域内部流转，不面向用户。若哪天 trade 要把券状态透传给前端，
 * 它必须自己投影（而不是"营销域直接返回 3"）——这条留给批次 4 的 trade 侧实现。
 */
public final class CouponStatusProjection {

    /**
     * "未知的对外状态"过滤时用的**不可能值**：{@code coupon_status} 是
     * {@code tinyint NOT NULL}，合法取值只有 {@code 0/1/2/3}，{@code -1} 永远不可能命中。
     */
    private static final int IMPOSSIBLE_STATUS = -1;

    private CouponStatusProjection() {
    }

    /**
     * 库里状态 → 对外状态：{@code LOCKED(3)} 投影成 {@code USED(1)}，其余原样。
     *
     * <p>{@code null} 按 {@code 0（未使用）}处理：列是 {@code NOT NULL DEFAULT 0}，
     * 正常读出来不可能是 null，这个分支只为"实体字段没填"这种程序错误兜底，
     * 而不是允许"状态未知"被当成一种合法对外值（返回 null 会让前端的
     * {@code couponStatus === 1} 判定静默失效）。
     *
     * @param dbStatus 库里读出的 {@code coupon_status}
     * @return 可以给前端的状态值（只可能是 0/1/2）
     */
    public static int toExternal(Integer dbStatus) {
        if (dbStatus == null) {
            return CouponMemberStatus.UNUSED;
        }
        if (dbStatus == CouponMemberStatus.LOCKED) {
            return CouponMemberStatus.USED;
        }
        return dbStatus;
    }

    /**
     * 对外 {@code ?status=} 过滤 → 需要匹配的**库里**状态集合。
     *
     * <ul>
     *   <li>{@code null}（没传参数）→ 返回 {@code null}，表示**不加过滤条件**；</li>
     *   <li>{@code 0} → {@code [0]}（锁定中的券**不得**出现在"未使用"页）；</li>
     *   <li>{@code 1} → {@code [1, 3]}（"已使用"页要同时包含锁定中的券，否则用户会以为券没了）；</li>
     *   <li>{@code 2} → {@code [2]}；</li>
     *   <li>其它值（含 {@code 3}）→ {@code [-1]}：<b>筛一个不可能命中的值</b>。
     *       这与旧实现一致（旧的 {@code .eq(couponStatus, status)} 传 5 就是空列表），
     *       而**不能**退化成"忽略过滤条件"——那会把用户全部券吐出来。
     *       尤其 {@code ?status=3} 不能命中库里的 LOCKED：{@code 3} 不在对外词表里，
     *       放它进来等于对外暴露了新状态。</li>
     * </ul>
     *
     * @param externalStatus 前端传来的 status 参数（可为 null）
     * @return 库里状态的匹配集合；{@code null} 表示不加过滤
     */
    public static List<Integer> dbStatusFilter(Integer externalStatus) {
        if (externalStatus == null) {
            return null;
        }
        return switch (externalStatus) {
            case CouponMemberStatus.UNUSED -> List.of(CouponMemberStatus.UNUSED);
            case CouponMemberStatus.USED -> List.of(CouponMemberStatus.USED, CouponMemberStatus.LOCKED);
            case CouponMemberStatus.EXPIRED -> List.of(CouponMemberStatus.EXPIRED);
            default -> List.of(IMPOSSIBLE_STATUS);
        };
    }

    /** 库里状态是否为"锁定中"（读路径判断用，避免各处手写 {@code == 3}） */
    public static boolean isLocked(Integer dbStatus) {
        return dbStatus != null && dbStatus == CouponMemberStatus.LOCKED;
    }
}
