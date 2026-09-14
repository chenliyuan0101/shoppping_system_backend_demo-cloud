package com.mall.admin.service;

import com.mall.admin.dto.MemberAdminVO;
import com.mall.admin.support.dto.PageResult;

import java.time.LocalDateTime;

/**
 * 后台会员管理（见《接口文档.md》3.7，需管理员登录）。
 *
 * <p>实现契约（P7 §4）：
 * <ul>
 *   <li><b>分页主查</b>在属主域（{@code mall-user-center} 的 {@code POST /internal/v1/user/member/page}）——
 *       total 与 list 必须同一时刻、同一个库算出来；</li>
 *   <li>拿到当页 id 后<b>一次批量</b>取补数（订单数/累计实付），**不许 N+1**；
 *       可执行判据是"远程调用次数与页码无关"；</li>
 *   <li>启停是<b>跨服务写</b>（属主域内保证"禁用即失效令牌"），本侧的缓存失效走事务提交之后。</li>
 * </ul>
 */
public interface AdminMemberService {

    /**
     * 会员分页。
     *
     * @param createTimeStart 注册时间起(含)，null 不限
     * @param createTimeEnd   注册时间止(不含)，null 不限
     */
    PageResult<MemberAdminVO> page(String keyword, Integer status,
                                   LocalDateTime createTimeStart, LocalDateTime createTimeEnd,
                                   long pageNum, long pageSize);

    MemberAdminVO detail(Long id);

    void updateStatus(Long id, Integer status);
}
