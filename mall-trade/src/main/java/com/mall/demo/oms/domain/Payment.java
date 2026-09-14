package com.mall.demo.oms.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 支付流水(模拟支付一单一笔)，对应 oms_payment。
 */
@Data
@TableName("oms_payment")
public class Payment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String payNo;

    private String orderNo;

    private Long memberId;

    private Long amount;

    /** 一期固定 MOCK */
    private String channel;

    /** 0失败 1成功 2已全额退回 */
    private Integer payStatus;

    private LocalDateTime payTime;

    private LocalDateTime createTime;
}
