package com.mall.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.marketing.domain.Coupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 券模板 Mapper（{@code sms_coupon} 的读写口）。
 *
 * <p>P5 批次 2 新增的两条是**领券的防超发/防重复**逻辑，逐字搬自单体
 * {@code com.mall.demo.sms.mapper.CouponMapper}（同一张表、同一段 SQL、同一个语义）：
 * <ul>
 *   <li>{@link #increaseReceived}：{@code received_count} 原子 +1，且**总量已满时影响 0 行**
 *       ——"检查是否领完"与"记一次领取"压在同一条 SQL 里。</li>
 *   <li>{@link #decreaseReceived}：并发重复领取撞唯一键时的**回滚补偿**（把 +1 抵消）。</li>
 * </ul>
 * 这两条不是"可以优化掉的冗余"：换成"先 select 判断再 update"就会在并发下超发
 * （两个请求都读到 {@code received_count < total_count}，各 +1）。
 *
 * <p>**建券/改券/启停/删券**仍属批次 3（后台 7 个端点），到那时再按真实 SQL 补方法。
 */
public interface CouponMapper extends BaseMapper<Coupon> {

    /** 领取量原子 +1：总量 NULL=不限；已领满则影响 0 行(防超发) */
    @Update("UPDATE sms_coupon SET received_count = received_count + 1 "
            + "WHERE id = #{id} AND (total_count IS NULL OR received_count < total_count)")
    int increaseReceived(@Param("id") Long id);

    /** 领取冲突回滚时原子 -1(不影响 0 行) */
    @Update("UPDATE sms_coupon SET received_count = received_count - 1 "
            + "WHERE id = #{id} AND received_count > 0")
    int decreaseReceived(@Param("id") Long id);
}
