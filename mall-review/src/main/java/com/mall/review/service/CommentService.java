package com.mall.review.service;

import com.mall.review.dto.CommentsResult;
import com.mall.review.dto.MineCommentVO;
import com.mall.review.dto.SubmitCommentRequest;
import com.mall.review.support.PageResult;
import com.mall.common.support.MemberId;

/**
 * 评价服务(见《接口文档.md》2.8)。
 *
 * <p>P4 批次 3：本接口连同实现整体从单体 {@code com.mall.demo.pms.service.CommentService}
 * 搬来，方法与签名逐字未改（对外的路径/入参/响应因此一字不变）。
 * 变的是<b>实现里数据的来源</b>：不再跨域读订单、不再跨域 CAS，
 * 全部改成本服务自己的 {@code pms_comment} + {@code review_pending_item}。
 */
public interface CommentService {

    /** 发表评价(订单须已完成且在可评价期内) */
    void submit(Long memberId, SubmitCommentRequest request);

    /** 商品评价分页(含好评率) */
    CommentsResult productComments(Long spuId, long pageNum, long pageSize);

    /** 我的评价分页 */
    PageResult<MineCommentVO> mine(Long memberId, long pageNum, long pageSize);
}
