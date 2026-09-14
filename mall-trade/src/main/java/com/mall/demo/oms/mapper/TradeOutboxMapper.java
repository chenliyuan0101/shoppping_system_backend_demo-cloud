package com.mall.demo.oms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.demo.oms.domain.TradeOutbox;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 发件箱 Mapper（P8-3）。
 *
 * <p>三条自定义 SQL 都**刻意用到 DDL 里那几个索引**（否则定时重投会全表扫）：
 * <ul>
 *   <li>{@link #findDue} → {@code idx_status_next (status, next_retry_at)}</li>
 *   <li>{@link #countHanging} → {@code idx_created (created_at)}（+ status 在这个规模下无所谓）</li>
 *   <li>{@link #markSent} / {@link #markFailed} 都是主键等值更新，且都带 {@code status = 0} 条件：
 *       "只有把待发送改成终态的那一次才返回 1" —— 这是并发重投时**不重复标记**的判据</li>
 * </ul>
 */
@Mapper
public interface TradeOutboxMapper extends BaseMapper<TradeOutbox> {

    /** 取"待发送且已到期"的一批，按 id 升序（= 入箱顺序，保证同一业务的事件不乱序） */
    @Select("""
            SELECT * FROM trade_outbox
             WHERE status = 0
               AND (next_retry_at IS NULL OR next_retry_at <= NOW())
             ORDER BY id ASC
             LIMIT #{limit}
            """)
    List<TradeOutbox> findDue(@Param("limit") int limit);

    /** 标记已发送：只有"当次从待发送改成已发送"才影响 1 行 */
    @Update("""
            UPDATE trade_outbox
               SET status = 1, sent_at = NOW(), last_error = NULL
             WHERE id = #{id} AND status = 0
            """)
    int markSent(@Param("id") Long id);

    /**
     * 标记失败：重试次数 +1、按退避排下次时间；**超过上限直接进"已放弃"(2)**。
     *
     * <p>用一条语句同时表达"再等等"和"别等了"：调用方不需要先读再判（避免多一次往返与竞态）。
     * {@code LEAST(..., 300)} 给退避封顶（5 分钟），避免重试次数多了之后等上几小时。
     *
     * <p>⚠️ **赋值顺序是有意的**：MySQL 的 {@code UPDATE ... SET} 子句**从左到右求值，后面的表达式会看到已被改过的值**。
     * 所以 `retry_count = retry_count + 1` 必须放在**最后**；否则 `status = IF(retry_count + 1 >= maxRetry, ...)`
     * 里的 `retry_count` 已经是 +1 之后的，等于**每次失败都多算一次重试**
     * （实测症状：`max-retry=2` 时第一次失败就把行标成"已放弃"，本套件第一版就是这么红的）。
     */
    @Update("""
            UPDATE trade_outbox
               SET status        = IF(retry_count + 1 >= #{maxRetry}, 2, 0),
                   next_retry_at = DATE_ADD(NOW(), INTERVAL LEAST(#{backoffSeconds} * (retry_count + 1), 300) SECOND),
                   last_error    = #{err},
                   retry_count   = retry_count + 1
             WHERE id = #{id} AND status = 0
            """)
    int markFailed(@Param("id") Long id,
                   @Param("err") String err,
                   @Param("maxRetry") int maxRetry,
                   @Param("backoffSeconds") int backoffSeconds);

    /**
     * 悬挂条数：入箱超过 {@code seconds} 秒仍**没发出去**的。
     * 这是"每日悬挂对账"的判据 —— 正常运行时它应该是 0。
     */
    @Select("""
            SELECT COUNT(*) FROM trade_outbox
             WHERE status = 0 AND created_at < DATE_SUB(NOW(), INTERVAL #{seconds} SECOND)
            """)
    int countHanging(@Param("seconds") int seconds);

    /** 已放弃条数（重试超限，需要人看） */
    @Select("SELECT COUNT(*) FROM trade_outbox WHERE status = 2")
    int countAbandoned();

    /** 按业务键查（排障用：一条订单的事件入箱/发送情况） */
    @Select("SELECT * FROM trade_outbox WHERE biz_key = #{bizKey} ORDER BY id ASC")
    List<TradeOutbox> findByBizKey(@Param("bizKey") String bizKey);

    /**
     * 归档（P8-4）：删掉"已发送且发送时间早于 N 天前"的行，单次限量。
     *
     * <p>为什么必须有一条：发件箱是**只增**的表（每次业务事件一行），不清理会无限增长
     * —— 这是 P8-3 报告里明确记账的遗留项。
     *
     * <p>三条边界都是刻意的：
     * <ul>
     *   <li>只删 {@code status = 1}（**已发送**）：待发送(0)/已放弃(2) 的行是"还没了结的事"，绝不能当垃圾清掉；</li>
     *   <li>按 {@code sent_at}（不是 {@code created_at}）算年龄：这样"卡了很久才发出去"的行也留足观察期；</li>
     *   <li>{@code LIMIT} 单次限量：避免一次删太多把库卡住（定时任务会一轮轮删完）。</li>
     * </ul>
     */
    @Delete("""
            DELETE FROM trade_outbox
             WHERE status = 1
               AND sent_at IS NOT NULL
               AND sent_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)
             ORDER BY id ASC
             LIMIT #{limit}
            """)
    int archiveSent(@Param("days") int days, @Param("limit") int limit);
}
