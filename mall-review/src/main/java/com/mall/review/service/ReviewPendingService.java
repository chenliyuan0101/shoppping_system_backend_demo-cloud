package com.mall.review.service;

import com.mall.review.mq.OrderFinishedMessage;

/**
 * 待评价读模型（{@code review_pending_item}）的写入入口。
 *
 * <p>本接口只有 P4-2 需要的一个动作：<b>把一条确认收货事件投影成待评价行</b>。
 * 提交评价时用到的那次"抢闸门"条件更新属于评价写业务（P4-3 搬过来时加在这里），
 * 本批**刻意不做**——闸门一旦写进来，就必须连同 409 文案与订单归属校验一起搬，
 * 那是另一个批次的范围。
 *
 * <p>为什么单独一层 Service 而不是让消费者直接调 mapper：
 * <ol>
 *   <li>一个事件的多条明细必须落在<b>同一个本地事务</b>里（要么全进，要么全不进，
 *       重复投递时靠主键幂等收敛），事务边界属于服务层；</li>
 *   <li>消费者的重试链路需要"抛异常 = 这次投递失败"这个信号，写成 Service 才能被
 *       {@code @Transactional} 与测试分别独立驱动。</li>
 * </ol>
 */
public interface ReviewPendingService {

    /**
     * 把一条 {@code order.finished} 事件投影成待评价行（幂等：同一 {@code order_itemId} 只落一行）。
     *
     * @param event 确认收货事件（其 {@code items} 为 null/空时什么都不做）
     * @return 本次<b>新插入</b>的行数；已存在的明细计入 0（重复投递的正常表现）
     */
    int projectOrderFinished(OrderFinishedMessage event);
}
