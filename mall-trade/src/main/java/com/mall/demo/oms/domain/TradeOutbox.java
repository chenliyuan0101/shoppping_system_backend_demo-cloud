package com.mall.demo.oms.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 交易域事件发件箱（表 {@code trade_outbox}，DDL 见 {@code mall-trade/db/03-trade-outbox.sql}）。
 *
 * <p><b>它解决什么</b>：P8 之前事件是"业务事务提交后直接投 MQ"，**进程在投递前崩掉 ⇒ 事件永久丢失**
 * （消费侧幂等做得再好也救不回来，因为消息根本没发出去）。发件箱把"要不要发"变成**业务事务里的一个 INSERT**：
 * 事务提交 ⇒ 事件一定在库里；发送失败或进程崩溃 ⇒ 由 {@code TradeOutboxRelay} 重投。
 *
 * <p><b>语义：至少一次（at-least-once）</b>。进程在"MQ 已收到、标记未发"之间崩溃会产生**重复投递**，
 * 这是刻意的取舍（相比"丢事件"，重复投递可由消费侧幂等消化，而丢事件不可恢复）。
 *
 * <p>字段与注释逐字对应 DDL，不额外加映射注解（MyBatis-Plus 默认下划线↔驼峰）。
 */
@Data
@TableName("trade_outbox")
public class TradeOutbox {

    /** 待发送 */
    public static final int STATUS_PENDING = 0;
    /** 已发送 */
    public static final int STATUS_SENT = 1;
    /** 已放弃（重试超限，需人工/对账处理） */
    public static final int STATUS_ABANDONED = 2;

    /** 主键（也用作投递顺序：按 id 升序发） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件类型（如 order.paid / order.closed / order.finished） */
    private String eventType;

    /** MQ 路由键（与消费方声明的拓扑逐字一致） */
    private String routingKey;

    /** 消息正文（JSON；**与直投路径逐字相同** —— 消费方不需要任何改动） */
    private String payload;

    /** 业务键（订单号等）：同时用作 MQ 的 messageId，便于排查与排重 */
    private String bizKey;

    /** 0=待发送 1=已发送 2=已放弃 */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 下次可重投时间（退避；NULL=立即可发） */
    private LocalDateTime nextRetryAt;

    /** 最后一次失败原因（运维可见；不含敏感信息） */
    private String lastError;

    /** 入箱时间（= 业务事务提交时间） */
    private LocalDateTime createdAt;

    /** 投递成功时间 */
    private LocalDateTime sentAt;
}
