package com.mall.review.support.dto;

import lombok.Data;

/**
 * 会员昵称的<b>最小契约快照</b>：只声明本服务真正消费的字段。
 *
 * <p>对应 user-center 的 {@code GET /internal/v1/user/member/{id}/snapshot}（其响应类型是
 * {@code MemberSnapshotVO}，字段更多：username/phone/avatar/status/createTime）。
 * 这里刻意只留 {@code id} + {@code nickname}：
 * <ul>
 *   <li>反序列化时未知字段被忽略（Spring Boot 的 ObjectMapper 默认 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}），
 *       所以"对方加字段"不会让本服务炸；</li>
 *   <li>反过来，本服务的代码里不存在"我拿到了会员手机号"这种能力——评价域只需要昵称。</li>
 * </ul>
 * 与 {@link CommentBriefVO} 同一取舍：跨服务只交换"已经定下来的那部分"。
 */
@Data
public class MemberNicknameVO {

    private Long id;

    private String nickname;
}
