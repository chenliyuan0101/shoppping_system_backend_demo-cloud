package com.mall.usercenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.usercenter.support.CacheService;
import com.mall.usercenter.support.dto.CartClaimResultVO;
import com.mall.usercenter.support.dto.CartItemSnapshotVO;
import com.mall.usercenter.domain.CartItem;
import com.mall.usercenter.mapper.CartItemMapper;
import com.mall.usercenter.service.CartCheckoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * 购物车结算契约实现：把"读要结算的条目"和"原子清空"这两步留在购物车域内。
 *
 * <p>实现刻意保持薄：不在这里做库存/价格校验（那是商品域的事，且必须在下单时重新校验），
 * 也不在这里拼展示字段。
 *
 * <h2>P3：两阶段闸门（claim / restore）</h2>
 * 旧的 {@code consumeItems} 依赖"删除动作会被调用方的事务回滚"——本地同库时成立，
 * 一旦购物车搬到 user-center（跨进程）就不成立：订单失败回滚时，条目已经被删掉且回不来。
 * 因此新增两阶段形态：
 * <ul>
 *   <li>{@link #claim}：先读明细 → 条件删除（影响行数仍是并发凭证）→ 把**归还所需的最小字段**
 *       存进 Redis（key 含 {@code orderNo}，TTL 24h）。同一 orderNo 重放直接回放快照；</li>
 *   <li>{@link #restore}：按快照把条目插回去（唯一键 {@code uk_member_sku} 保证幂等）。</li>
 * </ul>
 * <b>为什么快照里要有 spuId</b>：对外快照 {@link CartItemSnapshotVO} 只有 itemId/skuId/quantity
 * （刻意做窄，防止调用方顺手用购物车里的旧价格），但归还时 {@code ums_cart_item.spu_id} 是 NOT NULL。
 * "归还所需的最小字段"属于属主自己的实现细节，不该泄漏到跨服务契约里。
 *
 * <p><b>搬迁说明（P3-3）</b>：本类是购物车明细的<b>属主实现</b>，因此单体那份带
 * {@code @ConditionalOnProperty("mall.user-center.remote")} 的过渡期开关不再需要——
 * 过渡实现留在单体侧，切换完成后由单体调用本服务的 {@code /internal/v1/user/cart/**}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartCheckoutServiceImpl implements CartCheckoutService {

    /** 领取记录：{@code mall:idem:cart:claim:{memberId}:{orderNo}} → 明细快照 */
    private static final String CLAIM_KEY_PREFIX = "mall:idem:cart:claim:";

    /** 领取记录的保留时间：与下单幂等结果一致（24 小时），足够覆盖重试与人工排查 */
    private static final Duration CLAIM_TTL = Duration.ofHours(24);

    private final CartItemMapper cartItemMapper;
    private final CacheService cacheService;

    @Override
    @Transactional(readOnly = true)
    public List<CartItemSnapshotVO> items(Long memberId, Collection<Long> itemIds) {
        return targetRows(memberId, itemIds).stream()
                .map(row -> new CartItemSnapshotVO(row.getId(), row.getSkuId(), row.getQuantity()))
                .toList();
    }

    @Override
    @Transactional
    public CartClaimResultVO claim(Long memberId, String orderNo, Collection<Long> itemIds) {
        if (memberId == null || orderNo == null || orderNo.isBlank() || itemIds == null || itemIds.isEmpty()) {
            return CartClaimResultVO.notClaimed();
        }
        String key = claimKey(memberId, orderNo);

        // ① 幂等重放：同一订单再次调用，直接回放当时领到的明细（双击提交不该被判成冲突）
        List<ClaimedItem> previous = readClaim(key);
        if (previous != null) {
            return new CartClaimResultVO(true, previous.stream().map(ClaimedItem::toSnapshot).toList());
        }

        // ② 首次领取：先读明细（要写入归还快照），再条件删除——影响行数就是并发凭证
        List<CartItem> rows = targetRows(memberId, itemIds);
        if (rows.size() != distinctSize(itemIds)) {
            return CartClaimResultVO.notClaimed();
        }
        int removed = cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getMemberId, memberId)
                .in(CartItem::getId, itemIds));
        if (removed != rows.size()) {
            // 竞态：读与删之间被别的订单领走了
            return CartClaimResultVO.notClaimed();
        }

        List<ClaimedItem> claimed = rows.stream().map(ClaimedItem::of).toList();
        writeClaim(key, claimed);
        return new CartClaimResultVO(true, claimed.stream().map(ClaimedItem::toSnapshot).toList());
    }

    @Override
    @Transactional
    public boolean restore(Long memberId, String orderNo) {
        if (memberId == null || orderNo == null || orderNo.isBlank()) {
            return false;
        }
        String key = claimKey(memberId, orderNo);
        List<ClaimedItem> claimed = readClaim(key);
        if (claimed == null) {
            return false;   // 没领过（或已归还）：空操作
        }
        for (ClaimedItem item : claimed) {
            if (cartItemMapper.selectCount(new LambdaQueryWrapper<CartItem>()
                    .eq(CartItem::getMemberId, memberId)
                    .eq(CartItem::getSkuId, item.skuId())) > 0) {
                continue;   // 唯一键 uk_member_sku：已存在即视为已归还
            }
            CartItem row = new CartItem();
            row.setMemberId(memberId);
            row.setSpuId(item.spuId());
            row.setSkuId(item.skuId());
            row.setQuantity(item.quantity());
            row.setChecked(item.checked());
            cartItemMapper.insert(row);
        }
        cacheService.delete(key);   // 归还后清掉领取记录，避免重复归还
        return true;
    }

    // ---------- private ----------

    private List<CartItem> targetRows(Long memberId, Collection<Long> itemIds) {
        if (memberId == null || itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        return cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getMemberId, memberId)
                .in(CartItem::getId, itemIds));
    }

    private static int distinctSize(Collection<Long> itemIds) {
        return (int) itemIds.stream().distinct().count();
    }

    private static String claimKey(Long memberId, String orderNo) {
        return CLAIM_KEY_PREFIX + memberId + ":" + orderNo;
    }

    private List<ClaimedItem> readClaim(String key) {
        return cacheService.getJson(key, new TypeReference<List<ClaimedItem>>() {
        });
    }

    private void writeClaim(String key, List<ClaimedItem> items) {
        cacheService.setJson(key, items, CLAIM_TTL);
    }

    /** 领取记录（属主内部结构：比对外快照多一个 spuId，用于归还） */
    public record ClaimedItem(long itemId, long spuId, long skuId, int quantity, int checked) {

        static ClaimedItem of(CartItem row) {
            return new ClaimedItem(row.getId(), row.getSpuId(), row.getSkuId(), row.getQuantity(), row.getChecked());
        }

        CartItemSnapshotVO toSnapshot() {
            return new CartItemSnapshotVO(itemId, skuId, quantity);
        }
    }
}
