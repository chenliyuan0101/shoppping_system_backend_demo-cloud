package com.mall.usercenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.usercenter.support.dto.AddressSnapshotVO;
import com.mall.usercenter.domain.Address;
import com.mall.usercenter.mapper.AddressMapper;
import com.mall.usercenter.service.AddressQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import com.mall.common.support.MemberId;

/**
 * 地址只读契约实现："默认地址怎么选"这条规则留在地址域内，调用方只表达意图。
 */
/**
 * 过渡期开关（P3-4）：{@code mall.user-center.remote=false}（默认）用本进程实现，
 * 置 true 则改用 {@code UserCenterClient} 调 mall-user-center 的内部接口。
 *
 * <p>为什么用开关而不是直接替换：这样"切换数据源"这一动作本身可回退、可分批验证
 * （单体侧测试默认仍跑本地实现），切换完成后删除本地实现与这个注解。
 */
@ConditionalOnProperty(name = "mall.user-center.remote", havingValue = "false", matchIfMissing = true)@Service
@RequiredArgsConstructor
public class AddressQueryServiceImpl implements AddressQueryService {

    private final AddressMapper addressMapper;

    @Override
    @Transactional(readOnly = true)
    public AddressSnapshotVO defaultAddress(Long memberId) {
        if (memberId == null) {
            return null;
        }
        Address address = addressMapper.selectOne(new LambdaQueryWrapper<Address>()
                .eq(Address::getMemberId, memberId)
                .orderByDesc(Address::getIsDefault).orderByDesc(Address::getUpdateTime)
                .last("LIMIT 1"));
        return address == null ? null : toSnapshot(address);
    }

    @Override
    @Transactional(readOnly = true)
    public AddressSnapshotVO address(Long memberId, Long addressId) {
        if (memberId == null || addressId == null) {
            return null;
        }
        Address address = addressMapper.selectById(addressId);
        if (address == null || !Objects.equals(address.getMemberId(), memberId)) {
            return null;   // 不属于该会员时与"不存在"同样处理，不泄漏他人地址的存在性
        }
        return toSnapshot(address);
    }

    private static AddressSnapshotVO toSnapshot(Address a) {
        return new AddressSnapshotVO(a.getId(), a.getMemberId(), a.getReceiverName(), a.getReceiverPhone(),
                a.getProvinceName(), a.getCityName(), a.getDistrictName(), a.getDetail());
    }
}
