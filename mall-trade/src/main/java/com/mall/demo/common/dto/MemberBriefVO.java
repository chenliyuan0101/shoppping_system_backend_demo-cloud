package com.mall.demo.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会员概要（<b>域间契约</b>）：只带"别的域展示会员时需要的最小字段"。
 *
 * <p>为什么需要它：此前 oms（后台订单）与 pms（评价昵称）需要会员信息时，
 * 直接 `select` {@code ums_member} 并拿 {@code Member} 实体用——等于<b>把自己的表结构
 * 钉成别人的依赖</b>（架构闸门里的 B1/B2）。换成这个只读契约后，会员域之外谁都不碰那张表。
 *
 * <p>字段取舍：{@code username}/{@code nickname}/{@code phone} 是后台订单页要展示的三项；
 * <b>不含</b> {@code password}、{@code status} 等与展示无关的字段——
 * 契约只暴露消费方真正需要的，避免把整张表"顺手"带出去。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemberBriefVO {

    private Long id;

    /** 登录用户名 */
    private String username;

    /** 昵称（可能为空，展示方自行兜底文案） */
    private String nickname;

    private String phone;
}
