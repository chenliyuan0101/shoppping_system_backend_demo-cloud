package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.domain.Sku;
import com.mall.product.dto.SkuAggregate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SkuMapper extends BaseMapper<Sku> {

    /**
     * 按 SPU 聚合"在架 SKU"的最低价与总库存，用于 ES 全量重建时一次性取数(避免逐 SPU 查询)。
     *
     * <p>口径与前台货架 SQL(SpuMapper.SHELF_ORDER 的价格子查询 / 价格区间筛选)保持一致：
     * 仅统计 {@code deleted = 0 AND status = 1} 的 SKU。
     * 注意：注解 SQL 不经过 MP 的 @TableLogic，逻辑删除条件必须显式写出。
     */
    @Select("SELECT spu_id AS spuId, MIN(price) AS minPrice, SUM(stock) AS totalStock "
            + "FROM pms_sku WHERE deleted = 0 AND status = 1 GROUP BY spu_id")
    List<SkuAggregate> selectShelfAggregates();

    /** 单个 SPU 的在架 SKU 聚合(商品写操作后同步 ES 文档时使用，避免全表扫描) */
    @Select("SELECT spu_id AS spuId, MIN(price) AS minPrice, SUM(stock) AS totalStock "
            + "FROM pms_sku WHERE deleted = 0 AND status = 1 AND spu_id = #{spuId} GROUP BY spu_id")
    SkuAggregate selectShelfAggregateBySpu(@Param("spuId") long spuId);

    /**
     * 绕过逻辑删除读取 SKU（库存回补用）。
     *
     * <p>为什么需要：后台改商品是"逻辑删除旧 SKU + 重建"，逻辑删除后 {@code selectById} 返回 null；
     * 若回补逻辑因此跳过，历史订单的库存就**永久少一份且没有任何日志**。这里直接查原始行，
     * 行还在（哪怕 deleted=1）就照常回补，真的没了才告警。
     */
    @Select("SELECT id, spu_id, sku_code, spec_values, image, price, original_price, stock, sales, "
            + "status, deleted, create_time, update_time FROM pms_sku WHERE id = #{id}")
    Sku selectByIdIgnoreLogicDelete(@Param("id") Long id);

    /**
     * 绕过逻辑删除读取某 SPU 的全部 SKU（含已逻辑删除的历史行）。
     *
     * <p>用途：商品编辑是"全量重建 SKU"，需要按 skuCode 继承旧行的 image/sales，
     * 否则保存一次就把 SKU 图片清空、销量归零。
     */
    @Select("SELECT id, spu_id, sku_code, spec_values, image, price, original_price, stock, sales, "
            + "status, deleted, create_time, update_time FROM pms_sku WHERE spu_id = #{spuId}")
    List<Sku> selectListIgnoreLogicDeleteBySpu(@Param("spuId") Long spuId);
}
