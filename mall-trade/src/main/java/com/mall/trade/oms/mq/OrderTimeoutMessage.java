package com.mall.trade.oms.mq;

/**
 * 订单超时关单消息体(JSON 序列化，见 {@link OrderTimeoutPublisher})。
 *
 * <p>只带"足够定位订单"的信息，**不带业务状态快照**：消费端始终回库读取订单当前状态再决定是否关单，
 * 这样重复投递、乱序、消息延迟到达都是安全的（幂等判断在 {@code OrderServiceImpl#closeIfExpired}）。
 *
 * @param orderNo             订单号(幂等键)
 * @param payExpireTimeMillis 下单时的支付截止时间(毫秒)，仅用于日志与排查
 * @param delayMillis         投递时计算的延迟毫秒数(写入消息 TTL)，仅用于日志与排查
 */
public record OrderTimeoutMessage(String orderNo, long payExpireTimeMillis, long delayMillis) {
}
