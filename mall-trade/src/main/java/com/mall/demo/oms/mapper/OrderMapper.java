package com.mall.demo.oms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.demo.oms.domain.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /** 今日(时间段内)已支付销售额合计，聚合在 DB 完成 */
    @Select("SELECT COALESCE(SUM(pay_amount), 0) AS s FROM oms_order "
            + "WHERE pay_status IN (1, 2) AND pay_time >= #{start} AND pay_time < #{end}")
    Long sumPaidBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 按下单日分组统计订单数(条件与分组均在 DB) */
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS d, COUNT(*) AS cnt FROM oms_order "
            + "WHERE create_time >= #{start} GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d')")
    List<Map<String, Object>> countOrdersGroupByDay(@Param("start") LocalDateTime start);

    /** 按支付日分组统计已支付销售额(条件与分组均在 DB) */
    @Select("SELECT DATE_FORMAT(pay_time, '%Y-%m-%d') AS d, COALESCE(SUM(pay_amount), 0) AS amt FROM oms_order "
            + "WHERE pay_status IN (1, 2) AND pay_time >= #{start} GROUP BY DATE_FORMAT(pay_time, '%Y-%m-%d')")
    List<Map<String, Object>> sumPaidGroupByDay(@Param("start") LocalDateTime start);

    /**
     * <b>批量</b>会员订单口径摘要：一次查询取回多个会员的 {@code (订单数, 累计实付)}。
     *
     * <p><b>P7 新增（后台会员列表的"批量补数"）</b>：单体的 {@code memberOrderBrief} 是<b>逐会员</b>口径
     * （{@code selectCount} + {@code selectObjs} 两次查询），后台会员列表若按页内会员逐个调用，
     * 每页就是 {@code 2 × pageSize} 次查询——这就是规格 §4 明令禁止的 N+1。
     * 列表要的是"一页一次"，所以这里把口径搬成**一条聚合 SQL**：
     * <pre>
     *   orderCount : COUNT(*)                    —— 与 {@code memberOrderBrief} 的 selectCount 同口径
     *   paidAmount : SUM(pay_amount) WHERE pay_status = 1 —— 与同方法 selectObjs 求和同口径
     *   deleted = 0：{@code memberOrderBrief} 走的是 MyBatis-Plus 的 {@code selectCount}/{@code selectObjs}，
     *                逻辑删除条件由 {@code @TableLogic} 自动带上；自定义 {@code @Select} <b>不会</b>自动带，
     *                所以这里**显式**写出来（漏了它就会把已删订单算进去，且不会有任何编译/运行错误提示）
     * </pre>
     * ⚠️ 口径的权威实现在 {@code OrderStatQueryServiceImpl.memberOrderBrief}，改一处必须改另一处；
     * 两者的等价性由 {@code mall-admin} 的批量补数用例（同一会员单条 vs 批量必须相等）钉住。
     *
     * @param memberIds 会员 id（调用方保证非空；空集合会拼出 {@code IN ()} 语法错）
     * @return 每个有订单的会员一行：{@code memberId / cnt / paid}（**没有订单的会员不会出现在结果里**）
     */
    @Select({"<script>",
            "SELECT member_id AS memberId, COUNT(*) AS cnt,",
            "       COALESCE(SUM(CASE WHEN pay_status = 1 THEN pay_amount ELSE 0 END), 0) AS paid",
            "FROM oms_order WHERE deleted = 0 AND member_id IN",
            "<foreach collection='memberIds' item='mid' open='(' separator=',' close=')'>#{mid}</foreach>",
            "GROUP BY member_id",
            "</script>"})
    List<Map<String, Object>> memberOrderBriefs(@Param("memberIds") Collection<Long> memberIds);
}
