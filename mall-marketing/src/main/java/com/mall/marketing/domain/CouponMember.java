package com.mall.marketing.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import com.mall.common.support.MemberId;

/**
 * 用户领取的券，对应 {@code sms_coupon_member}。
 *
 * <p>{@code couponStatus}：0未使用 / 1已使用 / 2已过期 / <b>3锁定中（P5 三态新增）</b>
 * ——取值语义见 {@code support/constant/CouponMemberStatus}，对外投影见
 * {@code support/CouponStatusProjection}。
 *
 * <p>{@code orderNo} 的含义在 P5 变了：旧实现是"核销回填"（写下时券已 USED），
 * 新实现是"<b>锁定归属</b>"——{@code lock} 时写下，{@code unlock} 时清空，
 * {@code use} 时要求与请求里的 orderNo 相等。它同时是"这张券被哪一单占用"的唯一凭据，
 * 也是批次 5 每日对账的依据。
 *
 * <p>⚠️ 字段与单体 {@code com.mall.demo.sms.domain.CouponMember} 一致（表结构由 mysqldump 导出）。
 * {@code @TableName} 显式写上：本服务的库是 {@code mall_marketing}，表名不变（保持对称，便于删旧表）。
 */
@Data
@TableName("sms_coupon_member")
public class CouponMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private Long memberId;

    private Integer couponStatus;

    private LocalDateTime receiveTime;

    private LocalDateTime expireTime;

    private String orderNo;

    private LocalDateTime useTime;

    /**
     * 锁定时刻（P5 步骤 E 新增列 {@code lock_time}）。
     *
     * <p>为什么必须有它：{@code unlock} 依赖"关单路径被走到"，而 MQ 丢事件、服务重启、人工改库都可能让某次关单
     * 没触发解锁——那就成了"券永远锁着、用户既用不了也看不见"（旧实现干脆把它烧成 USED，问题更隐蔽）。
     * 有了这一列，每日对账才能问出"这张券被锁多久了"，超过阈值（{@code mall.marketing.stuck-lock-hours}）
     * 就解锁 + WARN，作为最后一道防线（方案 §4.3）。
     *
     * <p>生命周期与 {@code orderNo} 同步：{@code lock} 时写、{@code unlock} 时清空；
     * {@code use}（核销）**不清**——它是审计信息，清掉就看不出"这张券什么时候被占用的"。
     */
    private LocalDateTime lockTime;
}
