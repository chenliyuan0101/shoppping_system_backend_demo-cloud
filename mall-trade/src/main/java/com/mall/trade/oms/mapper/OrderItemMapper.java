package com.mall.trade.oms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.trade.oms.domain.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /** 商品销售额榜：只取已支付订单明细，按 spu 在 DB 内聚合排序后返回前 N 条 */
    @Select("SELECT oi.spu_id AS spuId, SUM(oi.total_amount) AS amount "
            + "FROM oms_order_item oi INNER JOIN oms_order o ON o.order_no = oi.order_no "
            + "WHERE o.pay_status IN (1, 2) "
            + "GROUP BY oi.spu_id ORDER BY amount DESC LIMIT #{limit}")
    List<Map<String, Object>> sumPaidAmountBySpu(@Param("limit") int limit);
}
