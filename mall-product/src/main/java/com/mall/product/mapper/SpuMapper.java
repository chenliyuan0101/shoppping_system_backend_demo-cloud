package com.mall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.product.domain.Spu;
import com.mall.product.dto.AdminProductQuery;
import com.mall.product.dto.ProductShelfQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品主表 Mapper。前台货架与后台商品列表的筛选/排序/分页都封装成本接口的具名函数，
 * 条件全部在 SQL 内完成；排序为白名单固定片段，不接受拼接。
 * 注意：注解 SQL 不经过 MP 的 @TableLogic，逻辑删除条件(deleted = 0)必须显式写出。
 */
@Mapper
public interface SpuMapper extends BaseMapper<Spu> {

    // ==================== 前台货架 ====================

    String SHELF_WHERE = """
            pms_spu.deleted = 0 AND pms_spu.status = 1
            <if test="q.keyword != null and q.keyword != ''">
              AND pms_spu.title LIKE CONCAT('%', #{q.keyword}, '%')
            </if>
            <if test="q.categoryIds != null and q.categoryIds.size() > 0">
              AND pms_spu.category_id IN
              <foreach collection="q.categoryIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
            </if>
            <if test="q.brandId != null">
              AND pms_spu.brand_id = #{q.brandId}
            </if>
            <if test="q.minPrice != null">
              AND NOT EXISTS (SELECT 1 FROM pms_sku ps
                   WHERE ps.spu_id = pms_spu.id AND ps.deleted = 0 AND ps.status = 1
                     AND ps.price &lt; #{q.minPrice})
            </if>
            <if test="q.maxPrice != null">
              AND EXISTS (SELECT 1 FROM pms_sku ps
                   WHERE ps.spu_id = pms_spu.id AND ps.deleted = 0 AND ps.status = 1
                     AND ps.price &lt;= #{q.maxPrice})
            </if>
            """;

    String SHELF_ORDER = """
            <choose>
              <when test="q.sort == 'priceAsc'">
                ORDER BY (SELECT MIN(ps.price) FROM pms_sku ps
                     WHERE ps.spu_id = pms_spu.id AND ps.status = 1 AND ps.deleted = 0) ASC, pms_spu.id ASC
              </when>
              <when test="q.sort == 'priceDesc'">
                ORDER BY (SELECT MIN(ps.price) FROM pms_sku ps
                     WHERE ps.spu_id = pms_spu.id AND ps.status = 1 AND ps.deleted = 0) DESC, pms_spu.id ASC
              </when>
              <when test="q.sort == 'newest'">
                ORDER BY pms_spu.create_time DESC, pms_spu.id ASC
              </when>
              <otherwise>
                ORDER BY pms_spu.sales DESC, pms_spu.id ASC
              </otherwise>
            </choose>
            """;

    @Select("<script>SELECT COUNT(*) FROM pms_spu WHERE " + SHELF_WHERE + "</script>")
    Long countShelf(@Param("q") ProductShelfQuery q);

    @Select("<script>SELECT * FROM pms_spu WHERE " + SHELF_WHERE + SHELF_ORDER
            + " LIMIT #{offset}, #{limit}</script>")
    List<Spu> pageShelf(@Param("q") ProductShelfQuery q,
                        @Param("offset") long offset,
                        @Param("limit") long limit);

    // ==================== 后台商品列表 ====================

    String ADMIN_WHERE = """
            <where>
              pms_spu.deleted = 0
              <if test="q.keyword != null and q.keyword != ''">
                AND pms_spu.title LIKE CONCAT('%', #{q.keyword}, '%')
              </if>
              <if test="q.categoryIds != null and q.categoryIds.size() > 0">
                AND pms_spu.category_id IN
                <foreach collection="q.categoryIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
              </if>
              <if test="q.brandId != null">
                AND pms_spu.brand_id = #{q.brandId}
              </if>
              <if test="q.status != null">
                AND pms_spu.status = #{q.status}
              </if>
              <if test="q.minPrice != null">
                AND NOT EXISTS (SELECT 1 FROM pms_sku ps
                     WHERE ps.spu_id = pms_spu.id AND ps.deleted = 0
                       AND ps.price &lt; #{q.minPrice})
              </if>
              <if test="q.maxPrice != null">
                AND EXISTS (SELECT 1 FROM pms_sku ps
                     WHERE ps.spu_id = pms_spu.id AND ps.deleted = 0
                       AND ps.price &lt;= #{q.maxPrice})
              </if>
            </where>
            """;

    // 后台默认按最近更新倒序
    String ADMIN_ORDER = " ORDER BY pms_spu.update_time DESC, pms_spu.id DESC ";

    @Select("<script>SELECT COUNT(*) FROM pms_spu " + ADMIN_WHERE + "</script>")
    Long countAdminProducts(@Param("q") AdminProductQuery q);

    @Select("<script>SELECT * FROM pms_spu " + ADMIN_WHERE + ADMIN_ORDER
            + " LIMIT #{offset}, #{limit}</script>")
    List<Spu> pageAdminProducts(@Param("q") AdminProductQuery q,
                                @Param("offset") long offset,
                                @Param("limit") long limit);
}
