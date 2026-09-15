package com.mall.trade.common.contract;

import com.mall.trade.common.PageResult;
import com.mall.trade.common.dto.MemberBriefVO;
import com.mall.trade.common.dto.MemberSnapshotVO;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import com.mall.common.support.MemberId;

/**
 * 会员域的<b>查询契约</b>（供单体里其它域使用）。
 *
 * <p>P3-4 起会员数据的属主是 {@code mall-user-center}（库 {@code mall_user}），
 * 单体不再持有 {@code ums_member}、也不再有自己的实现：本接口的实现一律由
 * {@link com.mall.trade.app.UserCenterRemoteConfig} 提供（调 {@code /internal/v1/user/member/**}）。
 *
 * <p>为什么从 {@code com.mall.trade.auth.service} 挪到 {@code common.contract}：
 * 会员域的实现（mapper/entity/Service）已经整体搬走，接口留在 {@code auth} 包会留下一个
 * "没有属主域的域服务接口"——而它现在的作用恰恰是<b>跨服务契约</b>（HTTP 形状被 {@code UserCenterClient}
 * 逐字实现），与 {@link com.mall.trade.common.dto.MemberBriefVO} 等契约快照同层才自洽。
 * 形状仍遵守《微服务改造方案.md》§2.8：
 * <ul>
 *   <li><b>只收发 DTO</b>：入参是 id 集合/字符串，出参是 {@link MemberBriefVO}；
 *       不含 Entity、不含 Mapper、不含 {@code LambdaQueryWrapper}；</li>
 *   <li><b>语义完整</b>：一个方法对应一次有业务含义的查询，而不是"把 Mapper 包一层"；</li>
 *   <li><b>可一对一变 HTTP</b>：每个方法都对应 user-center 的一个内部端点（调用方无需改业务逻辑）。</li>
 * </ul>
 *
 * <p>配套约定：{@code ums_member} 的读写只有会员属主域（现在是 user-center）能做；
 * 别的域需要会员信息时一律经过这里，因此"别人的表结构"不会再泄漏成别处的编译依赖。
 */
public interface MemberQueryService {

    /**
     * 取单个会员概要。
     *
     * @param memberId 会员 id；为 null 或会员不存在时返回 {@code null}
     */
    MemberBriefVO brief(Long memberId);

    /**
     * 批量取会员概要（用于列表页补数）。
     *
     * @param memberIds 会员 id 集合；为空或 null 时返回空列表
     * @return 存在的会员概要；<b>不存在的 id 不会出现在结果里</b>（调用方自行处理缺失）
     */
    List<MemberBriefVO> briefs(Collection<Long> memberIds);

    /**
     * 会员总数（后台看板用）。
     */
    long count();

    /**
     * 取单个会员档案快照（后台会员详情、登录态兜底都用它）。
     *
     * <p>⚠️ 这同时是 {@code MemberSession} 的兜底数据源：网关快路径的成员状态缓存未命中时，
     * 它按 id 取回 {@code nickname/status} 回填缓存——<b>不能退回直连本地表</b>，
     * 否则"刚在 user-center 注册的会员"在单体侧查不到，下单直接 401（P3-4 实测踩到）。
     *
     * @param memberId 会员 id；为 null 或会员不存在时返回 {@code null}
     */
    MemberSnapshotVO snapshot(Long memberId);

    /**
     * 会员分页查询（后台会员列表的<b>分页主查</b>）。
     *
     * <p>分页主查留在属主域：页面的过滤条件（关键字/状态/注册时间段）都作用在 {@code ums_member} 上，
     * 由拥有者来分页最自然；调用方拿到这一页之后，如果需要别的域的字段，
     * 应该用<b>批量接口补数</b>（§4.6），而不是在循环里逐条查。
     *
     * @param keyword         用户名/手机号/昵称模糊匹配；空白表示不过滤
     * @param status          状态过滤；null 表示不过滤
     * @param createTimeStart 注册时间起（含）；null 表示不限
     * @param createTimeEnd   注册时间止（不含）；null 表示不限
     * @param pageNum         页码，收敛到 [1, 10000]
     * @param pageSize        每页条数，收敛到 [1, 50]
     */
    PageResult<MemberSnapshotVO> page(String keyword, Integer status,
                                      LocalDateTime createTimeStart, LocalDateTime createTimeEnd,
                                      long pageNum, long pageSize);

    /**
     * 按关键字模糊检索会员 id（匹配用户名 / 手机号 / 昵称）。
     *
     * <p>供"后台订单按会员关键字筛选"使用：调用方先拿到 id 集合，再在自己的查询里按 id 过滤——
     * 这样"会员怎么被搜出来"的知识留在会员域，订单域不需要知道会员表长什么样。
     *
     * @param keyword 关键字；空白时返回空列表
     */
    List<Long> searchIds(String keyword);
}
