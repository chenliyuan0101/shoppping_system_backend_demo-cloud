package com.mall.trade.oms.mq;

import java.util.List;
import com.mall.common.support.MemberId;

/**
 * 确认收货事件载荷（P4）。
 *
 * <p>为什么单独一个 record、不复用 {@link OrderEventMessage}：后者被 paid/shipped/refund 三条事件共用，
 * 而 P4 需要的 items 明细快照只有这一条事件才有——把它塞进共用形状会改动
 * user-center 通知消费者的反序列化契约（那是一次跨服务的隐性破坏）。
 *
 * <p>items 里带 <b>展示所需的冗余快照</b>（标题/图片/数量）：评价域据此建"待评价"读模型，
 * 提交评价时就能完全本地校验，不必回头同步调用订单域（这正是 P4 要消除的耦合）。
 *
 * @param orderNo      订单号
 * @param memberId     会员 id
 * @param finishedTime 确认收货时间（epoch millis；评价期限从这里算）
 * @param items        订单明细快照
 */
public record OrderFinishedMessage(String orderNo, Long memberId, long finishedTime, List<Item> items) {

    /** 事件类型常量（与 {@code OrderEventMessage.TYPE_*} 同风格） */
    public static final String TYPE_ORDER_FINISHED = "ORDER_FINISHED";

    /** 明细快照：只放评价侧展示与校验需要的字段 */
    public record Item(long orderItemId, long spuId, Long skuId, String spuTitle, String skuImage, int quantity) {
    }
}