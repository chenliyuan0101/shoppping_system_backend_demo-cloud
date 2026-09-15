package com.mall.usercenter.service;

import com.mall.usercenter.domain.Address;
import com.mall.usercenter.dto.AddressSaveRequest;

import java.util.List;
import com.mall.common.support.MemberId;

/**
 * 收货地址服务(登录态，见《接口文档.md》2.5，数据归属本人)。
 */
public interface AddressService {

    List<Address> list(Long memberId);

    Address get(Long memberId, Long id);

    Long create(Long memberId, AddressSaveRequest request);

    void update(Long memberId, Long id, AddressSaveRequest request);

    void delete(Long memberId, Long id);

    void setDefault(Long memberId, Long id);
}
