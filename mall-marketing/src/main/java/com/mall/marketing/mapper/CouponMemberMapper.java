package com.mall.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.marketing.domain.CouponMember;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户券 Mapper：本服务的**状态机就住在这里的三条条件 UPDATE 里**（P5 三态的核心）。
 *
 * <h2>为什么用条件 UPDATE（而不是"先 select 判状态、再 updateById"）</h2>
 * 查与改之间的窗口会被并发穿透：同一张券可能被两个订单各锁一次、或"锁定的同时被核销"。
 * 把判定与写入压进同一条 SQL，**影响行数就是唯一凭证**——这与购物车结算闸门
 * （{@code CartCheckoutService#consumeItems}）、评价抢闸门（{@code review_pending_item}）
 * 是同一个套路（方案 §4.1 ①）。
 *
 * <h2>三条 SQL 各自的不变量</h2>
 * <ul>
 *   <li>{@link #lockUnused}：只从 {@code 0} 出发（{@code coupon_status = 0}）。
 *       影响 1 行 = 抢到；影响 0 行 = 别人先锁了/已用掉/已过期 → 调用方抛 409。
 *       {@code order_no} 与状态在**同一条 UPDATE**里写，所以不存在"锁上了但没记订单号"
 *       的中间态（那会让批次 5 的每日对账永远找不到这张券）。</li>
 *   <li>{@link #markUsed}：只从 {@code 3} 出发，且 {@code order_no} 必须等于该单
 *       ——"不是本单锁的券"因此改不动（幂等与防误伤都靠这个 {@code AND}）。</li>
 *   <li>{@link #markUnused}：只从 {@code 3} 出发，且 {@code order_no} 必须等于该单，
 *       并**清空 order_no**。清空是必须的：留着旧单号会让"这张券被哪一单占用"变成假信息，
 *       下次 {@code lock} 的幂等判据（状态 3 且同单）会误判成"本单已锁"。</li>
 * </ul>
 *
 * <p>⚠️ 三条 SQL 都带 {@code member_id = #{memberId}}：会员 id 是**服务端上下文**传进来的
 * （trade 从登录态得到，不是前端可填的），带上它意味着"即使 couponMemberId 被写错成别人的券，
 * 也动不了别人的券"——这是纵深防御，不是重复校验。
 *
 * <p>⚠️ 不使用 {@code updateById}（MyBatis-Plus 的通用更新）：它按主键无条件覆盖，
 * 无法表达"只有当前是 X 才改成 Y"，而这三条 SQL 的**全部价值**就在那个条件上。
 */
public interface CouponMemberMapper extends BaseMapper<CouponMember> {

    /**
     * 锁定：{@code UNUSED(0) → LOCKED(3)}，同时写下占用它的订单号。
     *
     * @return 影响行数：1 = 抢到（唯一凭证）；0 = 没抢到（调用方据此判 409 或幂等）
     */
    @Update("""
            UPDATE sms_coupon_member
               SET coupon_status = 3,
                   order_no = #{orderNo},
                   lock_time = #{lockTime}
             WHERE id = #{couponMemberId}
               AND member_id = #{memberId}
               AND coupon_status = 0
            """)
    int lockUnused(@Param("couponMemberId") Long couponMemberId,
                   @Param("memberId") Long memberId,
                   @Param("orderNo") String orderNo,
                   @Param("lockTime") LocalDateTime lockTime);

    /**
     * 核销：{@code LOCKED(3) → USED(1)}（{@code order_no} 必须匹配该单），写 {@code use_time}。
     *
     * <p>{@code use_time} 只在**真的从 3 改到 1** 的那一次写：影响 0 行时不执行任何更新，
     * 所以"重复 use 不会刷新核销时间"（幂等语义要求，见 {@code CouponCommandServiceImpl#use}）。
     *
     * @return 影响行数：1 = 首次核销；0 = 状态不对或不是本单锁的券
     */
    @Update("""
            UPDATE sms_coupon_member
               SET coupon_status = 1,
                   use_time = #{useTime}
             WHERE id = #{couponMemberId}
               AND member_id = #{memberId}
               AND coupon_status = 3
               AND order_no = #{orderNo}
            """)
    int markUsed(@Param("couponMemberId") Long couponMemberId,
                 @Param("memberId") Long memberId,
                 @Param("orderNo") String orderNo,
                 @Param("useTime") LocalDateTime useTime);

    /**
     * 解锁：{@code LOCKED(3) → UNUSED(0)}，清 {@code order_no}（**只动自己锁的那张**）。
     *
     * <p>刻意**不**清 {@code use_time}：解锁只会发生在"还没核销"的券上（状态 3），
     * 那种券的 {@code use_time} 本来就是 NULL；而如果把它写成"顺手置空"，
     * 一旦有人误用这条 SQL 去处理已核销的券，就会静默抹掉核销痕迹。
     *
     * @return 影响行数：1 = 解锁成功；0 = 不是本单锁的券（含"已被本单 use 掉"）
     */
    @Update("""
            UPDATE sms_coupon_member
               SET coupon_status = 0,
                   order_no = NULL,
                   lock_time = NULL
             WHERE id = #{couponMemberId}
               AND member_id = #{memberId}
               AND coupon_status = 3
               AND order_no = #{orderNo}
            """)
    int markUnused(@Param("couponMemberId") Long couponMemberId,
                   @Param("memberId") Long memberId,
                   @Param("orderNo") String orderNo);

    /**
     * 对账用：捞"锁太久"的券（{@code LOCKED(3)} 且 {@code lock_time} 早于阈值），按锁定时间升序。
     *
     * <p>{@code lock_time IS NULL} 的 {@code LOCKED} 行也要捞出来：那说明这一行是**历史遗留**
     * （加列之前就锁着的、或人工改库没写时间），对账正是要收拾这类行——只按"时间早于阈值"筛会永远漏掉它们。
     *
     * @param threshold 早于这个时刻即视为"锁太久"
     * @param limit     单轮上限（避免一次捞全表把服务拖住）
     */
    @Select("""
            SELECT id                      AS id,
                   template_id             AS templateId,
                   member_id               AS memberId,
                   coupon_status           AS couponStatus,
                   receive_time            AS receiveTime,
                   expire_time             AS expireTime,
                   order_no                AS orderNo,
                   use_time                AS useTime,
                   lock_time               AS lockTime
              FROM sms_coupon_member
             WHERE coupon_status = 3
               AND (lock_time IS NULL OR lock_time < #{threshold})
             ORDER BY (lock_time IS NULL) DESC, lock_time ASC
             LIMIT #{limit}
            """)
    List<CouponMember> selectStuckLocked(@Param("threshold") LocalDateTime threshold,
                                         @Param("limit") int limit);

    /**
     * 对账用：解锁一条"锁太久"的行（{@code 3 → 0}，清 {@code order_no} 与 {@code lock_time}）。
     *
     * <p><b>为什么不复用 {@link #markUnused}</b>：那条 SQL 要求 {@code order_no = #{orderNo}} 精确匹配
     * （那是"只动自己锁的那张"的防线，对正常解锁是对的）。但对账处理的是**历史遗留行**——
     * 可能 {@code order_no} 为空（加列前就锁着、或人工改库），用等值匹配会**永远解不开**。
     * 所以这里按 id + 状态 + 时间阈值三重条件解锁：
     * 只要它仍然是那一刻锁住的"同一张券"，就允许解开。
     *
     * @param threshold 与 {@link #selectStuckLocked} 用同一个阈值：保证"读到的"和"改的"是同一批，
     *                  避免"对账期间用户刚锁上的券被误解锁"
     * @return 影响行数：1 = 解锁成功；0 = 状态/时间已经变了（幂等，无需处理）
     */
    @Update("""
            UPDATE sms_coupon_member
               SET coupon_status = 0,
                   order_no = NULL,
                   lock_time = NULL
             WHERE id = #{id}
               AND coupon_status = 3
               AND (lock_time IS NULL OR lock_time < #{threshold})
            """)
    int unlockStuck(@Param("id") Long id, @Param("threshold") LocalDateTime threshold);

    /**
     * 读这张券的**当前已提交状态**（{@code FOR SHARE}：绕过 REPEATABLE READ 的一致性快照）。
     *
     * <p><b>为什么需要它（一个真实存在的窄竞态）</b>：MySQL 默认隔离级别是 REPEATABLE READ，
     * 事务内的普通 {@code SELECT} 读的是**事务第一次读时建立的快照**。于是"条件 UPDATE 影响 0 行
     * → 再普通读一次做幂等判断"这条路径会读到**过期快照**：
     * 两个并发请求用**同一个 orderNo** lock 同一张券时，后到的那个快照里状态还是 0，
     * 于是判不出"本单已锁"，把本该幂等返回 true 的调用判成 409。
     * （条件 UPDATE 本身用的是当前读，所以"只有一方拿到"这件事不受影响——
     * 受影响的只是**文案/返回值**。）
     *
     * <p>{@code FOR SHARE} 是当前读，且只加共享锁（读后立刻结束事务，不会成为热点）：
     * 它保证"影响 0 行之后的这次判断"看的是别人**已经提交**的真实状态。
     * 用它替代 {@code selectById} 的三处调用点都是"CAS 失败后的兜底判断"，
     * 而**前置校验**仍然用普通 {@code selectById}（无锁、不影响并发度；它只决定给哪条文案）。
     *
     * <p>⚠️ 只在事务内可用：{@code FOR SHARE} 无事务时会立刻释放锁，
     * 语义退化成普通读。三处调用点都在 {@code @Transactional} 方法里。
     *
     * <p>⚠️ 列名**显式起 camelCase 别名**，不依赖 {@code map-underscore-to-camel-case}：
     * 本服务的 MyBatis 装配是手写的（{@code MybatisPlusConfig}），全局开关没写在配置里，
     * 一旦它恰好是默认的 {@code false}，手写 SQL 返回的对象会**全是 null**
     * （BaseMapper 的方法不受影响——它走 MP 自己生成的 resultMap），
     * 于是"CAS 失败后的兜底判断"会静默失效：本该幂等返回 true 的重复调用全变 409。
     * 别名把这个隐患变成不可能。
     */
    @Select("""
            SELECT id                      AS id,
                   template_id             AS templateId,
                   member_id               AS memberId,
                   coupon_status           AS couponStatus,
                   receive_time            AS receiveTime,
                   expire_time             AS expireTime,
                   order_no                AS orderNo,
                   use_time                AS useTime,
                   lock_time               AS lockTime
              FROM sms_coupon_member
             WHERE id = #{couponMemberId}
             FOR SHARE
            """)
    CouponMember selectCurrentById(@Param("couponMemberId") Long couponMemberId);
}
