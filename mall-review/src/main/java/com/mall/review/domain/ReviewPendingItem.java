package com.mall.review.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待评价读模型，对应 {@code mall_review.review_pending_item}（表结构见 db/01-mall_review-schema.sql）。
 *
 * <p><b>这张表不是任何现有表的拷贝</b>：它是 P4-2 新增的投影，来源是 trade 在"确认收货"时发布的
 * {@code order.finished} 事件（消费者见 {@code com.mall.review.mq.OrderFinishedConsumer}）。
 * 有了它，提交评价时就<b>只查本地</b>——归属/是否已完成/剩余天数/是否已评价全部在 review 自己的库里判定，
 * 不再同步调用订单域（这是 P4 要消除的那条最硬的耦合）。
 *
 * <p><b>{@link #orderItemId} 是主键，这是本表的全部设计要点</b>（见建表脚本的注释）：
 * <ol>
 *   <li>事件是 at-least-once，重复投递是常态——主键 + {@code ON DUPLICATE KEY UPDATE} 让重复事件天然只落一行，
 *       不需要额外的幂等表或 Redis 幂等键；</li>
 *   <li>防重复评价的闸门是一次条件更新
 *       {@code UPDATE ... SET commented=1, comment_id=? WHERE order_item_id=? AND commented=0}，
 *       影响行数就是并发凭证（与 P3 的购物车闸门同构）。</li>
 * </ol>
 * 因此实体上 {@link TableId} 的类型必须是 {@link IdType#INPUT}（id 由事件给出，**不能**自增）。
 *
 * <p><b>刻意不映射 {@code create_time} / {@code update_time}</b>：两列都由数据库维护
 * （{@code DEFAULT CURRENT_TIMESTAMP} / {@code ON UPDATE CURRENT_TIMESTAMP}），
 * 映射进来只会多出两个"写入方其实是 DB"的字段，而重复投递时的"无变化"正是靠这个 DB 语义成立的。
 */
@Data
@TableName("review_pending_item")
public class ReviewPendingItem {

    /** 订单明细 ID：主键（事件幂等的天然去重键 + 抢闸门的判定列） */
    @TableId(value = "order_item_id", type = IdType.INPUT)
    private Long orderItemId;

    private String orderNo;

    /** 会员 ID（评价归属校验用） */
    private Long memberId;

    private Long spuId;

    private Long skuId;

    /** 商品标题快照（事件带来，不回头查商品域） */
    private String spuTitle;

    /** SKU 图片快照 */
    private String skuImage;

    private Integer quantity;

    /** 订单确认收货时间（算"剩余可评价天数"的基准） */
    private LocalDateTime finishedTime;

    /** 是否已评价 0否 1是（抢闸门的条件列） */
    private Integer commented;

    /** 抢闸门成功后写入的评价 ID（对账用）；由后续批次的提交评价写入，本批只做回填写入 */
    private Long commentId;
}
