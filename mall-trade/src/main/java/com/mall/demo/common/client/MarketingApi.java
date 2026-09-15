package com.mall.demo.common.client;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.PageResult;
import com.mall.demo.common.dto.AdminCouponSaveRequest;
import com.mall.demo.common.dto.AdminCouponVO;
import com.mall.demo.common.dto.CouponBriefVO;
import com.mall.demo.common.dto.CouponChangeResultVO;
import com.mall.demo.common.dto.CouponLockResultVO;
import com.mall.demo.common.dto.CouponRecordVO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.Map;

/**
 * <b>营销域内部契约的声明式接口</b>（Spring HTTP Interface，替换 {@link MarketingClient} 里原先手写的
 * {@code RestClient} 链）。
 *
 * <p>路径**逐字对齐** {@code mall-marketing} 的 {@code /internal/v1/marketing/**}（全部是 POST）：
 * <ul>
 *   <li>交易侧：{@code /coupon/discount}、{@code /coupon/usable}、{@code /coupon/lock}、{@code /coupon/unlock}、
 *       {@code /coupon/use}</li>
 *   <li>后台：{@code /admin/coupon/{page,create,{id}/update,{id}/enable,{id}/disable,{id}/delete,{id}/records}}</li>
 * </ul>
 *
 * <h2>两条纪律</h2>
 * <ul>
 *   <li><b>请求体仍是"Map 或 DTO 原样发"</b>：下游读的是 JSON 字段名（{@code memberId}/{@code goodsTotal}/…），
 *       所以这里声明成 {@code Map<String,Object>} 或具体 DTO，客户端类负责拼出与迁移前**同一个 map**；</li>
 *   <li><b>返回类型是下游原始响应体 {@code ApiResponse<T>}</b>：泛型不再靠人工传
 *       {@code ParameterizedTypeReference}（原先泛型擦除会导致 {@code data} 被反序列化成
 *       {@code LinkedHashMap}，调用方随后 {@code ClassCastException}，编译期看不出来——P3-4 实测踩过），
 *       解包与文案映射统一留在 {@link MarketingClient}。</li>
 * </ul>
 *
 * <p>⚠️ 出站头 {@code X-Internal-Token} / {@code X-Trace-Id} 由 {@code OutboundHeadersInterceptor}
 * 统一注入，这里与客户端类都**不再**手工写 {@code .header(...)}。
 */
@HttpExchange(url = "/internal/v1/marketing", contentType = "application/json")
public interface MarketingApi {

    // ==================== 交易侧（下单链路） ====================

    /** 抵扣试算（含封顶）；body {@code {memberId,couponMemberId,goodsTotal}}（{@code couponMemberId} 可为 null） */
    @PostExchange("/coupon/discount")
    ApiResponse<Long> discount(@RequestBody Map<String, Object> body);

    /** 该会员在该金额下可用的券；body {@code {memberId,goodsTotal}} */
    @PostExchange("/coupon/usable")
    ApiResponse<List<CouponBriefVO>> usable(@RequestBody Map<String, Object> body);

    /** 锁定券（{@code 0 → 3}）；body {@code {memberId,couponMemberId,orderNo}} */
    @PostExchange("/coupon/lock")
    ApiResponse<CouponLockResultVO> lock(@RequestBody Map<String, Object> body);

    /** 核销券（{@code LOCKED → USED}）；body {@code {memberId,couponMemberId,orderNo}} */
    @PostExchange("/coupon/use")
    ApiResponse<CouponChangeResultVO> use(@RequestBody Map<String, Object> body);

    /** 解锁券（补偿/关单专用）；body {@code {memberId,couponMemberId,orderNo}} */
    @PostExchange("/coupon/unlock")
    ApiResponse<CouponChangeResultVO> unlock(@RequestBody Map<String, Object> body);

    // ==================== 后台侧（薄转发） ====================

    /** 券分页；body {@code {keyword,status,pageNum,pageSize}}（{@code keyword}/{@code status} 可为 null） */
    @PostExchange("/admin/coupon/page")
    ApiResponse<PageResult<AdminCouponVO>> adminPage(@RequestBody Map<String, Object> body);

    @PostExchange("/admin/coupon/create")
    ApiResponse<Long> adminCreate(@RequestBody AdminCouponSaveRequest request);

    @PostExchange("/admin/coupon/{id}/update")
    ApiResponse<Void> adminUpdate(@PathVariable("id") Long id, @RequestBody AdminCouponSaveRequest request);

    /** 启用（下游收空对象 {@code {}} 作 body，与迁移前 {@code Map.of()} 一致） */
    @PostExchange("/admin/coupon/{id}/enable")
    ApiResponse<Void> adminEnable(@PathVariable("id") Long id, @RequestBody Map<String, Object> body);

    @PostExchange("/admin/coupon/{id}/disable")
    ApiResponse<Void> adminDisable(@PathVariable("id") Long id, @RequestBody Map<String, Object> body);

    @PostExchange("/admin/coupon/{id}/delete")
    ApiResponse<Void> adminDelete(@PathVariable("id") Long id, @RequestBody Map<String, Object> body);

    /** 领取记录分页；body {@code {pageNum,pageSize}} */
    @PostExchange("/admin/coupon/{id}/records")
    ApiResponse<PageResult<CouponRecordVO>> adminRecords(@PathVariable("id") Long id,
                                                        @RequestBody Map<String, Object> body);
}
