package com.mall.trade.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * RabbitMQ 自检结果(字段名与原 Map 键一一对应)。
 *
 * <p>broker 不可用时只有 enabled/fallbackScanIntervalMs/available/error，
 * 用 NON_NULL 保证该分支的 JSON 与改造前一致。
 *
 * <p>各队列的观测对象仍由 {@code AdminMqController#queueInfo} 组装
 * (name / exists / messages / consumers)，属于内部组装结构，形状不变。
 */
@Data
@Schema(description = "RabbitMQ 自检结果")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MqPingVO {

    /** MQ 总开关(mall.mq.enabled) */
    private boolean enabled;

    /** 超时关单兜底扫描周期(毫秒) */
    private long fallbackScanIntervalMs;

    /** broker 是否可用 */
    private Boolean available;

    /** 延迟队列(消息排队等到期，正常会随时间堆积) */
    private Map<String, Object> delayQueue;

    /** 关单工作队列(应长期接近 0) */
    private Map<String, Object> workQueue;

    /** 死信队列(应长期为 0) */
    private Map<String, Object> deadLetterQueue;

    /** 商品索引同步队列 */
    private Map<String, Object> productSyncQueue;

    /** 商品索引同步重试队列 */
    private Map<String, Object> productSyncRetryQueue;

    /** 商品索引同步死信队列 */
    private Map<String, Object> productSyncDlq;

    /** 领域事件队列 */
    private Map<String, Object> eventQueue;

    /** 领域事件重试队列 */
    private Map<String, Object> eventRetryQueue;

    /** 领域事件死信队列 */
    private Map<String, Object> eventDlq;

    /** 不可用时的错误摘要(可用时为 null) */
    private String error;
}
