package com.mall.trade.pms.service;

import com.mall.trade.common.dto.SpuSnapshotVO;

import java.util.List;

/**
 * 商品域的<b>看板统计契约</b>（供后台 / BFF 使用）。
 *
 * <p>与 {@link ProductQueryService} 的分工：那个是"按 id 取详情"（购物车/订单要用的点查），
 * 这个是"按条件聚合"（在架数、销量榜）。两者都属商品域，都只暴露契约 DTO。
 *
 * <p>同样是读路径：调用方按 §4.6 可以并发聚合 + 缓存 + 降级。
 *
 * <p><b>P4 批次 3：{@code commentCountByMember(Long)} 已删除</b>
 * （它读的是评价表，而评价表已经搬去 {@code mall-review}）。
 * 这正是盘点报告 R9 说的"这个契约接口必须拆开"——商品统计留在商品域、评价数属于评价域。
 * 后台会员详情里的 {@code commentCount} 由评论域的内部端点提供，
 * 属于后续批次（本批只保证"商品域里不再有任何读评价表的代码"）。
 */
public interface ProductStatQueryService {

    /** 在架（{@code status=1}）商品数 */
    long countEnabled();

    /**
     * 销量榜：在架商品按销量倒序取前 N。
     *
     * @param limit 条数，收敛到 [1, 20]
     * @return 有序列表（销量可能为 null，调用方按 0 处理）
     */
    List<SpuSnapshotVO> topBySales(int limit);
}
