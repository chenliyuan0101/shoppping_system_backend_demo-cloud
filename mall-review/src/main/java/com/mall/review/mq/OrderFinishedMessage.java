package com.mall.review.mq;

import java.util.List;
import com.mall.common.support.MemberId;

/**
 * 确认收货事件载荷（P4-2）——单体 {@code com.mall.demo.oms.mq.OrderFinishedMessage} 的契约副本。
 *
 * <p><b>字段名与顺序必须与单体逐字相同</b>：JSON 字段名来自记录组件名，改一个字等于改契约，
 * 发布会成功、消费端解析失败（消息直接进死信队列）。两侧由本服务的
 * {@code OrderFinishedMqMySqlTest} 与单体的发布端共同守着。
 *
 * <p>为什么单独一个 record、不复用单体的 {@code OrderEventMessage}（本服务刻意不复制它）：后者被
 * paid/shipped/refund 三条事件共用，而这里需要的 items 明细快照只有这一条事件才有——
 * 把它塞进共用形状会改动 user-center 通知消费者的反序列化契约（一次跨服务的隐性破坏）。
 *
 * <p>items 里带 <b>展示所需的冗余快照</b>（标题/图片/数量）：评价域据此建"待评价"读模型，
 * 提交评价时就能完全本地校验，不必回头同步调用订单域（这正是 P4 要消除的耦合）。
 * 本服务只管消费，不生产该事件。
 *
 * @param orderNo      订单号
 * @param memberId     会员 id；null 时本服务不写读模型（读模型的归属校验离不开它）
 * @param finishedTime 确认收货时间（epoch millis；评价期限从这里算，落库时按
 *                     业务时区 {@link com.mall.common.support.MallTime#ZONE} 转 datetime）
 * @param items        订单明细快照
 */
public record OrderFinishedMessage(String orderNo, Long memberId, long finishedTime, List<Item> items) {

    /** 事件类型常量（与单体 {@code OrderFinishedMessage.TYPE_ORDER_FINISHED} 同值） */
    public static final String TYPE_ORDER_FINISHED = "ORDER_FINISHED";

    /** 明细快照：只放评价侧展示与校验需要的字段 */
    public record Item(long orderItemId, long spuId, Long skuId, String spuTitle, String skuImage, int quantity) {
    }
}
