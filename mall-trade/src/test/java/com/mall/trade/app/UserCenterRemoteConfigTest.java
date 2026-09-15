package com.mall.trade.app;

import com.mall.trade.common.PageResult;
import com.mall.trade.common.client.UserCenterClient;
import com.mall.trade.common.dto.AddressSnapshotVO;
import com.mall.trade.common.dto.CartClaimResultVO;
import com.mall.trade.common.dto.CartItemSnapshotVO;
import com.mall.trade.common.dto.MemberBriefVO;
import com.mall.trade.common.dto.MemberSnapshotVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.mall.common.support.MemberId;

/**
 * 远程模式接线层的测试（P3-4）：把 {@code mall.user-center.remote=true} 之后真正生效的
 * 四个实现逐个钉住。
 *
 * <p>为什么这类测试必须在"切开关之前"有：切换之后出问题的表现是**远处**的（后台会员列表空了、
 * 下单报地址不存在、购物车明细丢了），排查要跨两个服务。这里用 mock 掉 {@code UserCenterClient}，
 * 把"接口方法 → 内部端点调用"的映射与**空值/短路语义**固定下来：
 * <ul>
 *   <li>空集合/空 id 不能真的发请求（既浪费往返，也避免"空 ids 全表扫"这类下游风险）；</li>
 *   <li>{@code snapshot} 必须走按 id 的快照端点，**不能**用检索接口反查（曾经的实现缺陷）；</li>
 *   <li>{@code consumeItems} 在远程模式下必须显式失败，而不是静默降级成"没抢到闸门"
 *       （后者会让下单失败原因变得无法解释）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserCenterRemoteConfigTest {

    @Mock
    private UserCenterClient client;

    @Test
    @DisplayName("[远程] 会员只读：brief/briefs/count/page/searchIds/snapshot 各自映射到对应端点")
    void memberQueryDelegates() {
        UserCenterRemoteConfig.RemoteMemberQuery query = new UserCenterRemoteConfig.RemoteMemberQuery(client);

        MemberBriefVO brief = new MemberBriefVO();
        brief.setId(7L);
        when(client.member(7L)).thenReturn(brief);
        assertThat(query.brief(7L)).isSameAs(brief);
        assertThat(query.brief(null)).as("空 id 不该发请求").isNull();

        when(client.members(List.of(7L))).thenReturn(List.of(brief));
        assertThat(query.briefs(List.of(7L))).hasSize(1);
        assertThat(query.briefs(List.of())).as("空集合不该发请求").isEmpty();
        assertThat(query.briefs(null)).isEmpty();
        verify(client, never()).members(List.of());

        when(client.memberCount()).thenReturn(801L);
        assertThat(query.count()).isEqualTo(801L);

        MemberSnapshotVO snapshot = new MemberSnapshotVO();
        snapshot.setId(7L);
        when(client.memberSnapshot(7L)).thenReturn(snapshot);
        assertThat(query.snapshot(7L)).isSameAs(snapshot);
        assertThat(query.snapshot(null)).isNull();

        PageResult<MemberSnapshotVO> page = PageResult.of(1, 1, 10, List.of(snapshot));
        when(client.memberPage(eq("k"), eq(1), any(), any(), eq(2L), eq(10L))).thenReturn(page);
        assertThat(query.page("k", 1, null, null, 2L, 10L)).isSameAs(page);

        when(client.searchMemberIds("k")).thenReturn(List.of(7L));
        assertThat(query.searchIds("k")).containsExactly(7L);

        // 关键：详情走的是按 id 的快照端点；memberPage 只应被显式的 page() 调用用掉一次
        // （曾经的实现用检索接口反查详情，这里用次数把它钉死）
        verify(client).memberSnapshot(7L);
        verify(client, times(1)).memberPage(anyString(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("[远程] 会员写：updateStatus → 内部端点；空参数直接 400（不落到下游）")
    void memberAdminDelegates() {
        UserCenterRemoteConfig.RemoteMemberAdmin admin = new UserCenterRemoteConfig.RemoteMemberAdmin(client);

        admin.updateStatus(7L, 0);
        verify(client).updateMemberStatus(7L, 0);

        assertThatThrownBy(() -> admin.updateStatus(null, 0)).hasMessageContaining("状态值仅支持");
        assertThatThrownBy(() -> admin.updateStatus(7L, null)).hasMessageContaining("状态值仅支持");
    }

    @Test
    @DisplayName("[远程] 地址：null 参数不请求下游；否则按 memberId/addressId 精确转发")
    void addressQueryDelegates() {
        UserCenterRemoteConfig.RemoteAddressQuery address = new UserCenterRemoteConfig.RemoteAddressQuery(client);

        AddressSnapshotVO vo = new AddressSnapshotVO();
        vo.setId(3L);
        when(client.defaultAddress(7L)).thenReturn(vo);
        when(client.address(7L, 3L)).thenReturn(vo);

        assertThat(address.defaultAddress(7L)).isSameAs(vo);
        assertThat(address.address(7L, 3L)).isSameAs(vo);
        assertThat(address.defaultAddress(null)).isNull();
        assertThat(address.address(null, 3L)).isNull();
        assertThat(address.address(7L, null)).isNull();
        // 原始类型参数无法用 matcher 表达 null，因此用"只到达下游一次"证明空参数被短路
        verify(client, times(1)).address(anyLong(), anyLong());
    }

    @Test
    @DisplayName("[远程] 购物车：claim/restore 转发；空参数短路；consumeItems 显式拒绝")
    void cartCheckoutDelegates() {
        UserCenterRemoteConfig.RemoteCartCheckout cart = new UserCenterRemoteConfig.RemoteCartCheckout(client);

        CartItemSnapshotVO item = new CartItemSnapshotVO(11L, 2001L, 2);
        when(client.cartItems(eq(7L), any())).thenReturn(List.of(item));
        assertThat(cart.items(7L, List.of(11L))).hasSize(1);
        assertThat(cart.items(7L, List.of())).as("空集合不该发请求").isEmpty();
        assertThat(cart.items(null, List.of(11L))).isEmpty();

        CartClaimResultVO claimed = new CartClaimResultVO(true, List.of(item));
        when(client.claimCartItems(eq(7L), eq("T1"), any())).thenReturn(claimed);
        assertThat(cart.claim(7L, "T1", List.of(11L)).claimed()).isTrue();

        // 下游返回 null（异常路径）→ 必须表达成"没领到"，而不是 NPE 或误判成功
        when(client.claimCartItems(eq(7L), eq("T2"), any())).thenReturn(null);
        assertThat(cart.claim(7L, "T2", List.of(11L)).claimed()).isFalse();

        assertThat(cart.claim(7L, null, List.of(11L)).claimed()).isFalse();
        assertThat(cart.claim(7L, "T3", List.of()).claimed()).isFalse();
        verify(client, never()).claimCartItems(anyLong(), eq("T3"), any());

        when(client.restoreCartItems(7L, "T1")).thenReturn(true);
        assertThat(cart.restore(7L, "T1")).isTrue();
        assertThat(cart.restore(null, "T1")).isFalse();

        assertThatThrownBy(() -> cart.consumeItems(7L, List.of(11L)))
                .as("远程模式下必须显式失败：它的语义依赖本地事务回滚，跨进程后不成立")
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("claim/restore");
    }
}
