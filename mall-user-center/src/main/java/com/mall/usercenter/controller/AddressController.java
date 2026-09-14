package com.mall.usercenter.controller;

import com.mall.usercenter.support.ApiResponse;
import com.mall.usercenter.support.MemberId;
import com.mall.usercenter.domain.Address;
import com.mall.usercenter.dto.AddressSaveRequest;
import com.mall.usercenter.dto.AddressVO;
import com.mall.usercenter.service.AddressService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收货地址 /api/address(见《接口文档.md》2.5，全部需会员登录，仅能操作本人地址)。
 */
@Tag(name = "用户-收货地址")
@RestController
@RequestMapping("/api/address")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @GetMapping("/list")
    public ApiResponse<List<AddressVO>> list(@MemberId Long memberId) {
        return ApiResponse.ok(addressService.list(memberId).stream().map(AddressController::toVo).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<AddressVO> get(@MemberId Long memberId,
                                      @Parameter(description = "地址ID", example = "1") @PathVariable Long id) {
        return ApiResponse.ok(toVo(addressService.get(memberId, id)));
    }

    @PostMapping
    public ApiResponse<Long> create(@MemberId Long memberId, @RequestBody AddressSaveRequest request) {
        return ApiResponse.ok(addressService.create(memberId, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@MemberId Long memberId,
                                    @Parameter(description = "地址ID", example = "1") @PathVariable Long id,
                                    @RequestBody AddressSaveRequest request) {
        addressService.update(memberId, id, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@MemberId Long memberId,
                                    @Parameter(description = "地址ID", example = "1") @PathVariable Long id) {
        addressService.delete(memberId, id);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/default")
    public ApiResponse<Void> setDefault(@MemberId Long memberId,
                                        @Parameter(description = "地址ID", example = "1") @PathVariable Long id) {
        addressService.setDefault(memberId, id);
        return ApiResponse.ok();
    }

    // ---------- private ----------

    /** 实体 → VO：只做字段搬运，字段名与实体对外 JSON 完全一致 */
    private static AddressVO toVo(Address address) {
        AddressVO vo = new AddressVO();
        vo.setId(address.getId());
        vo.setMemberId(address.getMemberId());
        vo.setReceiverName(address.getReceiverName());
        vo.setReceiverPhone(address.getReceiverPhone());
        vo.setProvinceCode(address.getProvinceCode());
        vo.setProvinceName(address.getProvinceName());
        vo.setCityCode(address.getCityCode());
        vo.setCityName(address.getCityName());
        vo.setDistrictCode(address.getDistrictCode());
        vo.setDistrictName(address.getDistrictName());
        vo.setDetail(address.getDetail());
        vo.setIsDefault(address.getIsDefault());
        vo.setCreateTime(address.getCreateTime());
        vo.setUpdateTime(address.getUpdateTime());
        return vo;
    }
}
