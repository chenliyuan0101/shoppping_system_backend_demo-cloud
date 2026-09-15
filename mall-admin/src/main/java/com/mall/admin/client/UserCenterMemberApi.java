package com.mall.admin.client;

import com.mall.admin.support.ApiResponse;
import com.mall.admin.support.dto.MemberSnapshotVO;
import com.mall.admin.support.dto.PageResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.Map;

/**
 * <b>会员域（后台会员）内部契约的声明式接口</b>（Spring HTTP Interface，替换原先手写的 {@code RestClient} 调用）。
 *
 * <h2>路径与请求体形状逐字对齐单体 {@code com.mall.demo.common.client.UserCenterClient}</h2>
 * 前缀 {@code /internal/v1/user} + 各方法路径拼出来与迁移前**同一个 URL**；
 * 请求体字段名（{@code keyword}/{@code createTimeStart}/{@code createTimeEnd}/{@code status}/
 * {@code pageNum}/{@code pageSize}）仍由客户端类组装（值的格式，即 {@code LocalDateTime#toString()} 的 ISO 形式，
 * 也在那边决定）——因为"切换前后 user-center 收到的请求必须是同一个请求"。
 *
 * <h2>为什么这层能有收益（而非"为了新写法而新写法"）</h2>
 * <ol>
 *   <li><b>泛型不再靠人工传 {@code ParameterizedTypeReference}</b>：分页那条是
 *       {@code ApiResponse<PageResult<MemberSnapshotVO>>}（**两层泛型**），原先靠匿名子类保住类型；
 *       一旦被擦除，Jackson 只能把 {@code data} 反序列化成 {@code LinkedHashMap}，
 *       调用方遍历列表时 {@code ClassCastException}，而**编译期完全看不出来**。
 *       声明式接口的返回类型是**方法签名的一部分**，框架从 {@code MethodParameter} 解析泛型 ⇒ 这个坑结构性消失。</li>
 *   <li><b>路径变量不再手写拼接</b>：原先 {@code "/member/" + memberId + "/status"} 是字符串拼接，
 *       现在是 {@code @PathVariable}（框架负责编码），"拼错一个斜杠"这类问题不会再有。</li>
 *   <li><b>出站头不再手工写</b>：{@code X-Internal-Token} / {@code X-Trace-Id} 由挂在
 *       {@code @LoadBalanced RestClient.Builder} 上（直连分支由 {@code OutboundRestClientFactory} 显式挂上）的
 *       {@code OutboundHeadersInterceptor} 统一注入，接口方法上不需要（也不应该）再写 {@code .header(...)}。</li>
 * </ol>
 *
 * <h2>这一层**不做**什么（刻意留给 {@link UserCenterMemberClient}）</h2>
 * <ul>
 *   <li><b>不处理错误语义</b>：传输异常 / 空响应 → {@code BusinessException(500, "系统繁忙，请稍后重试")}、
 *       业务码非 0 → **原样透传**（{@code 404 会员不存在}、{@code 400 状态值仅支持 0禁用 1正常} 必须活着到前端），
 *       都在客户端类里（那是"域语义"，不是"HTTP 形状"）。</li>
 *   <li><b>不打日志</b>：日志条数/级别由调用方定（会员侧要 ERROR，由 service 层决定）。</li>
 *   <li><b>不做降级</b>：降级是 service 层的策略。</li>
 * </ul>
 *
 * <p>⚠️ 返回类型是**下游的原始响应体** {@code ApiResponse<T>}（不是 {@code T}）：解包与文案映射统一在
 * 客户端类的 {@code unwrap} 里做，接口只负责"HTTP ↔ 类型"这一步。
 *
 * <p>⚠️ 路径前缀 {@code /internal/**}：网关有过滤器直接 404 这一前缀（外网永远看不到）。
 */
@HttpExchange(url = "/internal/v1/user", contentType = "application/json")
public interface UserCenterMemberApi {

    /** 会员分页（后台列表的**分页主查**；total 与 list 在会员域同一个库、同一时刻算出来） */
    @PostExchange("/member/page")
    ApiResponse<PageResult<MemberSnapshotVO>> page(@RequestBody Map<String, Object> body);

    /**
     * 会员档案快照（详情用）。
     *
     * <p>下游对"不存在（含逻辑删除）"返回 {@code code=0, data=null} ⇒ 这里就声明成
     * {@code ApiResponse<MemberSnapshotVO>}，而"null 即不存在"的语义由客户端类返回给调用方
     * （**不是异常**：调用方据此返回 404 语义）。
     */
    @GetExchange("/member/{id}/snapshot")
    ApiResponse<MemberSnapshotVO> snapshot(@PathVariable("id") long memberId);

    /** 会员总数（看板 summary 的 {@code memberCount}） */
    @GetExchange("/member/count")
    ApiResponse<Long> count();

    /** 改会员状态（后台启停）：属主域内保证"禁用即失效令牌"，BFF 不参与 */
    @PostExchange("/member/{id}/status")
    ApiResponse<Void> updateStatus(@PathVariable("id") long memberId, @RequestBody Map<String, Object> body);
}
