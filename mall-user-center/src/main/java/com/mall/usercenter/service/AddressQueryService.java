package com.mall.usercenter.service;

import com.mall.usercenter.support.dto.AddressSnapshotVO;
import com.mall.common.support.MemberId;

/**
 * 收货地址的<b>只读契约</b>（供其它域使用，主要是下单链路）。
 *
 * <p>形状遵守《微服务改造方案.md》§2.8：只收发 DTO，不含 Entity / Mapper。
 * 归属校验（"这个地址是不是你的"）由本域完成——它是地址域的规则，
 * 不该让每个调用方各写一遍 `if (!address.getMemberId().equals(memberId))`。
 *
 * <p>说明：地址的<b>增删改</b>不在这里（那是用户端的 {@code AddressService}，有完整 CRUD 语义）。
 * 本接口只回答下单需要的两个问题，调用方拿不到"改别人地址"的能力。
 */
public interface AddressQueryService {

    /**
     * 取该会员的默认收货地址。
     *
     * <p>选择规则（与改造前一致）：优先 {@code is_default=1}，其次最近更新的一条；
     * 没有地址时返回 {@code null}。
     */
    AddressSnapshotVO defaultAddress(Long memberId);

    /**
     * 按 id 取该会员的收货地址。
     *
     * @return 地址快照；<b>地址不存在、或不属于该会员时返回 {@code null}</b>
     *         （两种情况不区分，避免探测他人的地址是否存在）
     */
    AddressSnapshotVO address(Long memberId, Long addressId);
}
