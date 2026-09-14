package com.mall.demo.oms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.demo.oms.domain.OrderDailyStat;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 订单按日统计 Mapper：两条"重算式 upsert"。
 *
 * <p>拆成两条是因为数据来自两张表（订单 / 售后）：
 * <ul>
 *   <li>第 1 条算订单侧 3 列（下单数按创建时间；支付笔数/金额按**支付时间**，跨天支付也能归到支付当天），
 *       第 2 条算售后侧 2 列，互不覆盖（各自的 ON DUPLICATE KEY 只更新自己的列）</li>
 *   <li>聚合恒返回一行，所以"某天没有订单/没有售后"也会把对应列刷新成 0（自愈）</li>
 * </ul>
 */
@Mapper
public interface OrderDailyStatMapper extends BaseMapper<OrderDailyStat> {

    /** 重算某天的订单侧指标（下单数按创建时间；支付笔数/金额按**支付时间**） */
    @Insert("""
            INSERT INTO oms_order_daily_stat
              (stat_date, order_count, paid_count, paid_amount, refund_count, refund_amount, update_time)
            SELECT #{date},
                   (SELECT COUNT(*) FROM oms_order
                     WHERE deleted = 0 AND create_time >= #{date} AND create_time < DATE_ADD(#{date}, INTERVAL 1 DAY)),
                   (SELECT COUNT(*) FROM oms_order
                     WHERE deleted = 0 AND pay_status >= 1
                       AND pay_time >= #{date} AND pay_time < DATE_ADD(#{date}, INTERVAL 1 DAY)),
                   (SELECT COALESCE(SUM(pay_amount), 0) FROM oms_order
                     WHERE deleted = 0 AND pay_status >= 1
                       AND pay_time >= #{date} AND pay_time < DATE_ADD(#{date}, INTERVAL 1 DAY)),
                   0, 0, NOW()
            ON DUPLICATE KEY UPDATE
              order_count = VALUES(order_count),
              paid_count  = VALUES(paid_count),
              paid_amount = VALUES(paid_amount),
              update_time = NOW()
            """)
    int refreshOrderSide(@Param("date") LocalDate date);

    /** 重算某天的售后侧指标（退款完成笔数 / 退款金额） */
    @Insert("""
            INSERT INTO oms_order_daily_stat
              (stat_date, order_count, paid_count, paid_amount, refund_count, refund_amount, update_time)
            SELECT #{date}, 0, 0, 0,
                   COALESCE(COUNT(*), 0),
                   COALESCE(SUM(refund_amount), 0), NOW()
              FROM oms_refund
             WHERE status = 2
               AND finish_time >= #{date} AND finish_time < DATE_ADD(#{date}, INTERVAL 1 DAY)
            ON DUPLICATE KEY UPDATE
              refund_count  = VALUES(refund_count),
              refund_amount = VALUES(refund_amount),
              update_time   = NOW()
            """)
    int refreshRefundSide(@Param("date") LocalDate date);
}
