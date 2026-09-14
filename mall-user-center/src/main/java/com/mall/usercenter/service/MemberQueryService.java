package com.mall.usercenter.service;

import com.mall.usercenter.support.PageResult;
import com.mall.usercenter.support.dto.MemberBriefVO;
import com.mall.usercenter.support.dto.MemberSnapshotVO;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 会员域的<b>查询契约</b>（供其它域使用）。
 *
 * <p>形状遵守《微服务改造方案.md》§2.8：
 * <ul>
 *   <li><b>只收发 DTO</b>：入参是 id 集合/字符串，出参是 {@link MemberBriefVO}；
 *       不含 Entity、不含 Mapper、不含 {@code LambdaQueryWrapper}；</li>
 *   <li><b>语义完整</b>：一个方法对应一次有业务含义的查询，而不是"把 Mapper 包一层"；</li>
 *   <li><b>可一对一变 HTTP</b>：拆分后本接口整体变成
 *       {@code /internal/v1/user/member/**} 之类的内部端点，调用方无需改业务逻辑。</li>
 * </ul>
 *
 * <p>配套约定：{@code ums_member} 的读写只有本服务能做；
 * 别的服务需要会员信息时一律经过这里（HTTP 内部接口），因此"别人的表结构"不会再泄漏成别处的编译依赖。
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
     * 取单个会员档案快照（后台会员详情用）。
     *
     * @param memberId 会员 id；为 null 或会员不存在时返回 {@code null}
     */
    MemberSnapshotVO snapshot(Long memberId);

    /**
     * 会员分页查询（后台会员列表的<b>分页主查</b>）。
     *
     * <p>分页主查留在会员域：页面的过滤条件（关键字/状态/注册时间段）都作用在 {@code ums_member} 上，
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
