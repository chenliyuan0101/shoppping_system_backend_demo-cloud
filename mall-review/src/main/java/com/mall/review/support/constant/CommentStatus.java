package com.mall.review.support.constant;

/**
 * 评价状态：{@code pms_comment.status} 的取值集合（**注意不是 0/1 启用极性**）。
 *
 * <p>取值来自现网 DDL 注释（{@code db/01-mall_review-schema.sql} 逐字导出自
 * {@code mall.pms_comment}）：{@code 0待审核 1展示 2隐藏}。
 *
 * <p><b>为什么要单独一个类，而不是复用 {@link EnableStatus}</b>：
 * 这是本项目里最容易出错的一处"同名列不同语义"——{@code status} 在 {@code pms_spu}
 * 是 0下架/1上架，在 {@code sms_coupon} 是 0启用/1停用（极性相反），
 * 在这里是"三态"且 0 的含义是**待审核而不是停用**。
 * P4 的评价展示、管理端审核、好评率统计都要按这个集合判断，
 * 所以把它写成常量而不是在各处写裸数字 0/1/2。
 *
 * <p>对外的可评价性判定（是否计入"好评率"）目前只认 {@link #VISIBLE}：
 * 待审核与被隐藏的评价不参与统计——这与单体 {@code CommentServiceImpl} 的口径一致，
 * 搬过来时必须保持（C1：对外数值不变）。
 */
public final class CommentStatus {

    /** 待审核：新建评价的默认状态，只有管理端审核后才展示 */
    public static final int PENDING = 0;

    /** 展示中：正常对外可见，计入数量与好评率 */
    public static final int VISIBLE = 1;

    /** 已隐藏：管理端下架的评价，不计入统计（但行还在） */
    public static final int HIDDEN = 2;

    private CommentStatus() {
    }
}
