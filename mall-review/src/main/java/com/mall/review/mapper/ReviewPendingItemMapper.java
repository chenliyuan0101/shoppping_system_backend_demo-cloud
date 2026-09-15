package com.mall.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.review.domain.ReviewPendingItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import com.mall.common.support.MemberId;

/**
 * 待评价读模型的 Mapper（只扫本服务的 {@code com.mall.review.mapper} 包，见 MybatisPlusConfig）。
 *
 * <p>为什么需要一个自定义 {@link #upsert} 而不是用 {@code BaseMapper.insert}：
 * MQ 的投递语义是 <b>at-least-once</b>，重复投递是常态（重试、重投、broker 重启）。
 * 用 {@code insert} 撞主键会抛 {@code DuplicateKeyException}，消费者只能把"正常重复"当成失败去重投/进死信——
 * 把幂等做成了错误处理。改为 {@code ON DUPLICATE KEY UPDATE} 后，重复投递是一次<b>无变化</b>的更新：
 *
 * <pre>
 * INSERT ... ON DUPLICATE KEY UPDATE order_item_id = order_item_id   -- 自赋值：明确的"什么都不改"
 * </pre>
 *
 * <p>三条刻意的取舍：
 * <ol>
 *   <li><b>自赋值而不是 {@code VALUES(col)}</b>：MySQL 8.0.20 起 {@code VALUES()} 已废弃，
 *       而自赋值不依赖任何版本特性，且语义更准确——"重复事件不做有意义的变化"，
 *       不是"重复事件把字段刷成同样的值"（后者会触发 {@code ON UPDATE CURRENT_TIMESTAMP}，
 *       把 {@code update_time} 改掉，等于改变了行状态）；</li>
 *   <li>不用 {@code INSERT IGNORE}：它会连"数据超长/类型不符"一起吞掉，
 *       而真正非幂等的错误必须暴露给消费者的重试链路；</li>
 *   <li>返回值直接用 MyBatis 的"影响行数"：新增 1 / 重复 0（无变化）——消费者据此打日志，
 *       不需要再 SELECT 一次。</li>
 * </ol>
 */
@Mapper
public interface ReviewPendingItemMapper extends BaseMapper<ReviewPendingItem> {

    /**
     * 幂等写入一条待评价明细：已存在则<b>不做任何有意义的变化</b>。
     *
     * @return 影响行数：1 = 新增；0 = 已存在（重复投递，属正常）
     */
    @Insert("""
            INSERT INTO review_pending_item
                (order_item_id, order_no, member_id, spu_id, sku_id, spu_title, sku_image, quantity,
                 finished_time, commented, comment_id)
            VALUES
                (#{orderItemId}, #{orderNo}, #{memberId}, #{spuId}, #{skuId}, #{spuTitle}, #{skuImage},
                 #{quantity}, #{finishedTime}, #{commented}, #{commentId})
            ON DUPLICATE KEY UPDATE order_item_id = order_item_id
            """)
    int upsert(ReviewPendingItem item);

    /**
     * 抢闸门：把某条明细从"未评价"条件更新为"已评价"，并记下评价 id。
     *
     * <p><b>影响行数就是并发凭证</b>（与 P3 的购物车闸门同构）：
     * {@code 1} = 抢到（本次是首次评价，调用方可以继续写评价行）；
     * {@code 0} = 没抢到（这条明细已被别的请求/历史回填标成已评价）→ 调用方按业务冲突处理。
     *
     * <p>为什么判定必须落在一条 UPDATE 上、而不是"先 SELECT 再判断"：
     * 后者在并发下有窗口（两个请求同时读到 {@code commented=0}，随后都写入评价行）；
     * 条件更新由数据库一次原子判定，应用层不需要窗口，也不需要额外的分布式锁。
     *
     * <p>为什么 {@code comment_id} 一起写：抢到闸门之后这条明细才第一次拥有"评价 id"，
     * 一次 UPDATE 写进去省掉第二个写操作，也让对账（"这条明细最终评的是哪条评论"）永远有值。
     *
     * @param orderItemId 订单明细 id（主键）
     * @param commentId   本次写入的评价 id
     * @return 影响行数：1 = 抢到；0 = 已被评价（含历史回填的 {@code commented=1} 行）
     */
    @Update("""
            UPDATE review_pending_item
               SET commented = 1, comment_id = #{commentId}
             WHERE order_item_id = #{orderItemId} AND commented = 0
            """)
    int claim(@Param("orderItemId") long orderItemId, @Param("commentId") Long commentId);
}
