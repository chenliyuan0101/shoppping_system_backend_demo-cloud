package com.mall.usercenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mall-user-center：用户中心服务（会员 / 登录态 / 地址 / 购物车 / 收藏 / 足迹 / 站内消息）。
 *
 * <p>它是 P3 抽出的服务，也是整个改造里<b>最敏感</b>的一个：
 * <ul>
 *   <li>{@code ums_member} 是改造前被 4 个域共享的表（oms 查会员、admin 管会员、pms 取评价昵称、sms 校验领券），
 *       它搬走之后"共享表"才算真正被消灭（C2 的第一个实质达成）；</li>
 *   <li>登录态从"每个服务自己验签 + 查库"改成"网关验签 + 身份透传"（§4.4），
 *       这一步影响**全站每一个需要登录的接口**，因此必须分批次、每批都能跑。</li>
 * </ul>
 *
 * <p>数据库：{@code mall_user}（6 张表，见 {@code db/01-mall_user-schema.sql}）。
 */
@SpringBootApplication
public class UserCenterApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserCenterApplication.class, args);
    }
}
