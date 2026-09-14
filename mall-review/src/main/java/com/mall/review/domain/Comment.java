package com.mall.review.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品评价，对应 {@code mall_review.pms_comment}（表结构见 db/01-mall_review-schema.sql）。
 *
 * <p><b>表是现网 {@code mall.pms_comment} 的逐字拷贝</b>（列顺序、类型、索引都照抄），
 * 只有一处新增列 {@link #memberNickname}：方案 §4.5 ③ 要求的"昵称冗余快照"，
 * 用来切断"展示评论要按 member_id 去 ums_member 取昵称"这条 pms→auth 的跨域读。
 *
 * <p>与源表一致的两个细节，别"顺手改"：
 * <ul>
 *   <li><b>没有 {@code update_time}</b>：评价一旦写入就不可改（删除走逻辑删除），
 *       因此源表本来就没有这一列，实体也不能凭空加一个（那样 select 会报未知列）；</li>
 *   <li>{@code images} 映射成 {@code String}：库里是 MySQL 的 {@code json} 类型，
 *       读写都当字符串处理，转换交给 {@code JsonKit}（与单体 {@code CommentServiceImpl} 一致）。</li>
 * </ul>
 *
 * <p>逻辑删除由 {@link TableLogic} 处理：{@code deleted = 1} 的行在
 * {@code selectById}/{@code selectCount} 里天然不可见——这就是"少一条评价"与
 * "查不到这条评价"语义的来源，调用方不需要自己写 {@code deleted = 0}。
 */
@Data
@TableName("pms_comment")
public class Comment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    /** 昵称历史快照（P4 新增列；写入时落库，之后不随会员改名变化） */
    private String memberNickname;

    /** 订单号（评价来源） */
    private String orderNo;

    /** 订单明细 ID（一单一评的关联粒度） */
    private Long orderItemId;

    private Long spuId;

    private Long skuId;

    /** 评分 1-5 */
    private Integer rating;

    private String content;

    /** 晒图 URL 数组的 JSON 字符串 */
    private String images;

    /** 0待审核 1展示 2隐藏（见 support.constant.CommentStatus，**不是** 0/1 启用极性） */
    private Integer status;

    /** 逻辑删除 0否 1是 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
}
