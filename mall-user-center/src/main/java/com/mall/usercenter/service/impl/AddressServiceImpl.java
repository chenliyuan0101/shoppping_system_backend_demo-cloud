package com.mall.usercenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.usercenter.support.BusinessException;
import com.mall.usercenter.support.constant.YesNo;
import com.mall.usercenter.support.RequestValidator;
import com.mall.usercenter.domain.Address;
import com.mall.usercenter.dto.AddressSaveRequest;
import com.mall.usercenter.mapper.AddressMapper;
import com.mall.usercenter.service.AddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 收货地址实现：仅能操作本人地址(水平越权防护在 requireOwned)。
 */
@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    private final AddressMapper addressMapper;
    /** 请求参数校验(约束注解写在 DTO 字段上，此处统一触发) */
    private final RequestValidator requestValidator;

    @Override
    @Transactional(readOnly = true)
    public List<Address> list(Long memberId) {
        return addressMapper.selectList(new LambdaQueryWrapper<Address>()
                .eq(Address::getMemberId, memberId)
                .orderByDesc(Address::getIsDefault)
                .orderByDesc(Address::getUpdateTime));
    }

    @Override
    @Transactional(readOnly = true)
    public Address get(Long memberId, Long id) {
        return requireOwned(memberId, id);
    }

    @Override
    @Transactional
    public Long create(Long memberId, AddressSaveRequest request) {
        validate(request);
        Address address = toEntity(memberId, new Address(), request);
        if (Boolean.TRUE.equals(request.getIsDefault())) {
            clearDefault(memberId);
            address.setIsDefault(YesNo.YES);
        } else {
            address.setIsDefault(YesNo.NO);
        }
        addressMapper.insert(address);
        return address.getId();
    }

    @Override
    @Transactional
    public void update(Long memberId, Long id, AddressSaveRequest request) {
        Address address = requireOwned(memberId, id);
        if (StringUtils.hasText(request.getReceiverName())) {
            address.setReceiverName(request.getReceiverName().trim());
        }
        if (StringUtils.hasText(request.getReceiverPhone())) {
            if (!PHONE.matcher(request.getReceiverPhone()).matches()) {
                throw new BusinessException(400, "收货人手机号格式不正确");
            }
            address.setReceiverPhone(request.getReceiverPhone());
        }
        if (StringUtils.hasText(request.getDetail())) {
            address.setDetail(request.getDetail());
        }
        // 区域：整组提供才更新(部分字段可空)
        boolean regionTouched = request.getProvinceCode() != null || request.getCityCode() != null
                || request.getDistrictCode() != null;
        if (regionTouched) {
            if (!StringUtils.hasText(request.getProvinceCode()) || !StringUtils.hasText(request.getCityCode())
                    || !StringUtils.hasText(request.getDistrictCode())) {
                throw new BusinessException(400, "请提交完整的省市区");
            }
            address.setProvinceCode(request.getProvinceCode());
            address.setCityCode(request.getCityCode());
            address.setDistrictCode(request.getDistrictCode());
            address.setProvinceName(request.getProvinceName());
            address.setCityName(request.getCityName());
            address.setDistrictName(request.getDistrictName());
        }
        if (Boolean.TRUE.equals(request.getIsDefault())) {
            clearDefault(memberId);
            address.setIsDefault(YesNo.YES);
        }
        addressMapper.updateById(address);
    }

    @Override
    public void delete(Long memberId, Long id) {
        // 说明：本模型订单保存地址快照、不引用地址 id，故不做"使用中禁止删除"校验
        requireOwned(memberId, id);
        addressMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void setDefault(Long memberId, Long id) {
        requireOwned(memberId, id);
        clearDefault(memberId);
        Address update = new Address();
        update.setId(id);
        update.setIsDefault(YesNo.YES);
        addressMapper.updateById(update);
    }

    // ---------- private ----------

    private Address requireOwned(Long memberId, Long id) {
        Address address = addressMapper.selectById(id);
        if (address == null || !address.getMemberId().equals(memberId)) {
            throw new BusinessException(404, "地址不存在");
        }
        return address;
    }

    private void clearDefault(Long memberId) {
        Address update = new Address();
        update.setIsDefault(YesNo.NO);
        addressMapper.update(update, new LambdaQueryWrapper<Address>()
                .eq(Address::getMemberId, memberId).eq(Address::getIsDefault, YesNo.YES));
    }

    private Address toEntity(Long memberId, Address target, AddressSaveRequest request) {
        if (memberId != null) {
            target.setMemberId(memberId);
        }
        target.setReceiverName(request.getReceiverName());
        target.setReceiverPhone(request.getReceiverPhone());
        target.setProvinceCode(request.getProvinceCode());
        target.setProvinceName(request.getProvinceName());
        target.setCityCode(request.getCityCode());
        target.setCityName(request.getCityName());
        target.setDistrictCode(request.getDistrictCode());
        target.setDistrictName(request.getDistrictName());
        target.setDetail(request.getDetail());
        return target;
    }

    private void validate(AddressSaveRequest request) {
        requestValidator.check(request);
        if (!StringUtils.hasText(request.getProvinceCode()) || !StringUtils.hasText(request.getCityCode())
                || !StringUtils.hasText(request.getDistrictCode())) {
            throw new BusinessException(400, "请选择完整的省市区");
        }
        if (!StringUtils.hasText(request.getDetail())) {
            throw new BusinessException(400, "请输入详细地址");
        }
    }
}
