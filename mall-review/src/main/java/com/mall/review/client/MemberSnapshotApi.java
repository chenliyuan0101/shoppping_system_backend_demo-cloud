package com.mall.review.client;

import com.mall.review.support.ApiResponse;
import com.mall.review.support.dto.MemberNicknameVO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import com.mall.common.support.MemberId;

/**
 * <b>会员域内部契约的声明式接口</b>（Spring HTTP Interface，替换原先手写的 {@code RestClient} 调用）。
 *
 * <h2>为什么这层能有收益</h2>
 * 原先在 {@code MemberSnapshotClient#nickname} 里混着三件事：拼路径（{@code SNAPSHOT_PATH} 常量 + 模板变量）、
 * 手工写 {@code X-Internal-Token} 头、以及泛型 {@code ParameterizedTypeReference}。
 * HTTP Interface 把前两件（"HTTP 形状"）挪到这里，客户端类只剩"域语义"。
 *
 * <h2>这一层**不做**什么（刻意留给 {@link MemberSnapshotClient}）</h2>
 * <ul>
 *   <li><b>不判业务码、不登录日志、不降级</b>：{@code code != 0} / {@code data == null} 都**不在这里抛**，
 *       而是把下游原始响应体交回客户端类——它要按"昵称取不到 ⇒ 空串，绝不影响评价提交"的口径收敛
 *       （连 WARN 的措辞与条数都在那边定）；</li>
 *   <li><b>不打日志</b>：日志由客户端类（以及最终调用 {@code CommentServiceImpl}）定。</li>
 * </ul>
 *
 * <p>⚠️ 返回类型是**下游的原始响应体** {@code ApiResponse<T>}（不是 {@code T}）：与改造前
 * {@code MemberSnapshotClient} 的公开方法签名/行为保持一致，调用方一行都不用改。
 */
@HttpExchange(url = "/internal/v1/user", contentType = "application/json")
public interface MemberSnapshotApi {

    /**
     * 会员档案快照（P3 就已存在于 user-center，本服务只是消费方）。
     *
     * <p>昵称是展示用冗余字段：这里对"没有这个会员"（{@code code != 0} / {@code data:null}）
     * 与"读不到"（传输失败）都保持中立 —— 两种回答的差别的处理在客户端类里。
     */
    @GetExchange("/member/{id}/snapshot")
    ApiResponse<MemberNicknameVO> snapshot(@PathVariable("id") Long memberId);
}
