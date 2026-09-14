package com.mall.marketing.support;

import com.mall.marketing.support.constant.CouponMemberStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>对外状态投影的单测（P5 批次 1 的唯一"纯单测"，不起 Spring 上下文）。</b>
 *
 * <p>为什么本批就要有这个单测：库里新增了 {@code LOCKED = 3}，而对外词表**仍然只有 0/1/2**
 * （前端两个工程已把它钉死，方案 §4.3.1 ②③）。对外端点在批次 2/3 才上线，
 * 但投影规则现在就必须被钉住——否则上线那天"锁定中的券在对外响应里变成 3"
 * 会同时改动两处查询，而两处里只要漏一处，同一张券就会在"我的券"与"发放记录"两个页面
 * 显示成不同状态。
 *
 * <p>本类**不复用** {@link MarketingTestBase}：投影是纯函数，连真库没有意义；
 * 放在这里也让它能在"MySQL 没起"的机器上单独跑（它守的是一条与数据库无关的契约）。
 */
class CouponStatusProjectionTest {

    // ==================================================================
    // ① 值投影：库里 3 → 对外 1
    // ==================================================================

    @Test
    @DisplayName("[投影] 库里 LOCKED(3) → 对外输出 1（'已使用'），这是 C1 硬约束")
    void lockedProjectsToUsed() {
        assertEquals(CouponMemberStatus.USED, CouponStatusProjection.toExternal(CouponMemberStatus.LOCKED),
                "锁定中的券对外必须显示'已使用'：旧实现里下单瞬间券就已 USED，"
                        + "锁定窗口内对外表现不能变，否则用户第一次看到'未使用却不能用'的券");
        assertEquals(1, CouponStatusProjection.toExternal(3));
    }

    @Test
    @DisplayName("[投影] 既有三个值原样透传（0/1/2 的对外词表一个字都不能动）")
    void existingStatusesPassThrough() {
        assertEquals(0, CouponStatusProjection.toExternal(CouponMemberStatus.UNUSED));
        assertEquals(1, CouponStatusProjection.toExternal(CouponMemberStatus.USED));
        assertEquals(2, CouponStatusProjection.toExternal(CouponMemberStatus.EXPIRED));
    }

    @Test
    @DisplayName("[投影] null 按 0 处理（列是 NOT NULL，这个分支只为程序错误兜底，绝不返回 null）")
    void nullBecomesUnused() {
        // 返回 null 会让前端的 `couponStatus === 1` 判定静默失效（页面显示成"未使用"却无法使用）
        assertEquals(0, CouponStatusProjection.toExternal(null));
    }

    @Test
    @DisplayName("[投影] isLocked 只认 3（读路径不该各处手写 == 3）")
    void isLockedOnlyMatchesThree() {
        assertTrue(CouponStatusProjection.isLocked(CouponMemberStatus.LOCKED));
        assertFalse(CouponStatusProjection.isLocked(CouponMemberStatus.USED));
        assertFalse(CouponStatusProjection.isLocked(null));
    }

    // ==================================================================
    // ② 过滤投影：?status=1 → IN (1,3)
    // ==================================================================

    @Test
    @DisplayName("[过滤] ?status=1 → 命中 IN (1,3)：锁定中的券必须出现在'已使用'页")
    void filterUsedMatchesUsedAndLocked() {
        List<Integer> db = CouponStatusProjection.dbStatusFilter(1);
        assertEquals(List.of(1, 3), db, "'已使用'页必须同时包含库里的 3，否则用户会以为券凭空消失了");
        assertTrue(db.contains(CouponMemberStatus.LOCKED), "锁定中的券要在'已使用'页里");
        assertFalse(db.contains(CouponMemberStatus.UNUSED), "'已使用'页绝不能包含未使用的券");
    }

    @Test
    @DisplayName("[过滤] ?status=0 → 只筛 0：锁定中的券不得出现在'未使用'页")
    void filterUnusedOnlyZero() {
        assertEquals(List.of(0), CouponStatusProjection.dbStatusFilter(0),
                "'未使用'页只有真正能用的券；锁定中的券（别人单子占着）出现在这里就会'点了却用不了'");
    }

    @Test
    @DisplayName("[过滤] ?status=2 → 只筛 2（已过期不包含锁定中的券）")
    void filterExpiredOnlyTwo() {
        assertEquals(List.of(2), CouponStatusProjection.dbStatusFilter(2));
    }

    @Test
    @DisplayName("[过滤] 不传 status → null（不加过滤条件），而不是空集合")
    void filterNullMeansNoFilter() {
        assertNull(CouponStatusProjection.dbStatusFilter(null),
                "null 表示'不加过滤'；返回空集合会让调用方生成 IN ()（SQL 语法错）或静默返回空列表");
    }

    @Test
    @DisplayName("[过滤] 未知/越界 status → 筛一个不可能命中值（与旧实现一致：返回空列表，不是忽略过滤）")
    void filterUnknownMatchesNothing() {
        // 旧实现是 .eq(couponStatus, status)：传 5 就是空列表。新实现不能退化成"过滤条件被忽略"，
        // 那会把用户的全部券（含锁定中的）一次性吐出来。
        for (int unknown : new int[]{3, 5, -1, 99}) {
            List<Integer> db = CouponStatusProjection.dbStatusFilter(unknown);
            assertEquals(List.of(-1), db, "status=" + unknown + " 应该筛一个库里不可能出现的值");
            assertFalse(db.contains(CouponMemberStatus.LOCKED),
                    "尤其 ?status=3 不能命中库里的 LOCKED：3 不在对外词表里，放它进来等于对外暴露新状态");
        }
    }

    // ==================================================================
    // ③ 两条规则必须自洽：过滤出来的一行，投影回去必须等于过滤用的那个 status
    // ==================================================================

    @Test
    @DisplayName("[自洽] ?status=1 筛出的每一行（含库里的 3）投影回去都等于 1")
    void filterAndProjectionAgreeForUsed() {
        for (Integer dbStatus : CouponStatusProjection.dbStatusFilter(1)) {
            assertEquals(1, CouponStatusProjection.toExternal(dbStatus),
                    "过滤与投影是两条规则，必须互为逆运算，否则同一页面会出现两种状态");
        }
    }

    @Test
    @DisplayName("[自洽] ?status=0 / ?status=2 同理")
    void filterAndProjectionAgreeForOthers() {
        assertEquals(0, CouponStatusProjection.toExternal(CouponStatusProjection.dbStatusFilter(0).get(0)));
        assertEquals(2, CouponStatusProjection.toExternal(CouponStatusProjection.dbStatusFilter(2).get(0)));
    }
}
