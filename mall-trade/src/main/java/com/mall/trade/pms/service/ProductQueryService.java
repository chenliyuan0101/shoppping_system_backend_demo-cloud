package com.mall.trade.pms.service;

import com.mall.trade.common.dto.HomeFeedVO;
import com.mall.trade.common.dto.SkuSnapshotVO;
import com.mall.trade.common.dto.SpuSnapshotVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 商品域的<b>只读查询契约</b>（供其它域使用）。
 *
 * <p>形状遵守《微服务改造方案.md》§2.8：只收发 DTO（{@link SkuSnapshotVO}/{@link SpuSnapshotVO}），
 * 不含 Entity / Mapper；将来抽 `mall-product` 服务时，本接口整体变成
 * {@code /internal/v1/product/sku/batch} 之类的内部端点。
 *
 * <p><b>为什么全是"批量"接口</b>：这些方法服务的是列表页（购物车、收藏、足迹）。
 * 如果只提供单条查询，调用方在列表里逐行调用就会变成 N 次远程往返——
 * 方案 §2.5 的原则三明确禁止"分页循环里逐条远程调用"，所以契约在设计上就只给批量形状。
 *
 * <p>配套约定：{@code pms_sku}/{@code pms_spu} 的读写只有本域能做；
 * 别的域需要商品信息一律经过这里。注意<b>"读"不改变状态</b>——
 * 库存扣减/回补属于交易链路，走的是另外的预占接口（§4.2），不在这里。
 */
public interface ProductQueryService {

    /** 单个 SKU 快照；不存在（或被逻辑删除）时返回 {@code null} */
    SkuSnapshotVO sku(Long skuId);

    /**
     * 批量 SKU 快照。
     *
     * @param skuIds id 集合；为空或 null 时返回空列表；<b>不存在的 id 不会出现在结果里</b>
     */
    List<SkuSnapshotVO> skus(Collection<Long> skuIds);

    /** 单个 SPU 快照；不存在（或被逻辑删除）时返回 {@code null} */
    SpuSnapshotVO spu(Long spuId);

    /** 批量 SPU 快照；语义同 {@link #skus(Collection)} */
    List<SpuSnapshotVO> spus(Collection<Long> spuIds);

    /**
     * 批量取"每个 SPU 下<b>启用</b> SKU 的最低价"（收藏/足迹列表展示"起售价"用）。
     *
     * <p>口径与改造前一致：只看 {@code status=1} 的 SKU、忽略价格为空的 SKU；
     * <b>没有任何可用 SKU 的 SPU 不会出现在返回的 Map 里</b>（调用方按 0 处理）。
     * 批量而非单条，是为了避免列表页逐行查询（N+1）。
     */
    Map<Long, Long> minEnabledSkuPrices(Collection<Long> spuIds);

    /**
     * 首页商品区块（类目树 + 热门 + 新品），<b>一次往返取全</b>。
     *
     * <p>这是本契约里唯一的"组合读"：调用方是内容域的首页聚合，且首页要求
     * "要么整块拿到、要么整块降级"，拆成三个方法反而让跨进程调用变成三个超时点。
     * 语义与前台货架完全一致（仅上架商品、仅启用类目），口径见
     * {@link #minEnabledSkuPrices(Collection)} 同域的 {@code ProductPortalService}。
     *
     * @param size 每个区块的条数；非法值（≤0 或 &gt;50）按默认 8 处理，
     *             由本域夹取——不信任调用方传参，避免一次请求把整张货架拖出来
     */
    HomeFeedVO homeFeed(int size);
}
