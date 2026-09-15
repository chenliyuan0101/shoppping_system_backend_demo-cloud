package com.mall.review.support.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import com.mall.common.support.MemberId;

/**
 * 评价的**跨服务契约快照**（不是持久层实体）。
 *
 * <p>为什么不让内部接口直接返回 {@code domain.Comment}：实体是持久层类型，
 * 把它当域间契约意味着"我加一个列，你的反序列化就可能炸"，而这正是 P0
 * {@code ModuleBoundaryTest} 的 B2 规则（"域间不得依赖别人的 domain"）要拦的事。
 * 这里显式定义一个 VO，字段是**已经定下来的契约**；对齐的是方案 §4.5 里
 * "商品详情页评论列表"要展示的字段。
 *
 * <p>字段口径（对齐改造前首页/详情页的评论展示）：
 * <ul>
 *   <li>{@code memberNickname} 取 {@code pms_comment.member_nickname} 快照，
 *       **不再**去 user-center 查会员——这是 P4 去掉 pms→auth 那条跨域边的落点；</li>
 *   <li>{@code images} 是晒图 URL 数组（库里是 JSON 字符串，由 {@code JsonKit} 转出来）；</li>
 *   <li>{@code status} 见 {@code support.constant.CommentStatus}（0待审核/1展示/2隐藏），
 *       **不是** 0/1 启用极性；</li>
 *   <li>不含 {@code deleted}：逻辑删除的行对调用方不存在（实体上的 {@code @TableLogic} 已过滤）。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentBriefVO {

    private Long id;

    private Long memberId;

    /** 昵称历史快照（评论写入时落库，不随会员改名变化） */
    private String memberNickname;

    private String orderNo;

    private Long orderItemId;

    private Long spuId;

    private Long skuId;

    /** 评分 1-5 */
    private Integer rating;

    private String content;

    /** 晒图 URL 数组 */
    private List<String> images;

    /** 0待审核 1展示 2隐藏（见 CommentStatus） */
    private Integer status;

    private LocalDateTime createTime;
}
