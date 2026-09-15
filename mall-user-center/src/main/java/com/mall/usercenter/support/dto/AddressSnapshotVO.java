package com.mall.usercenter.support.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.mall.common.support.MemberId;

/**
 * 收货地址快照（域间契约）：下单时"取一次地址、把值写进订单"。
 *
 * <p>背景：订单表本来就冗余了收件人姓名/电话/完整地址（历史快照语义，订单不该随地址修改而变），
 * 因此下单只需要<b>读一次</b>地址，此后订单与地址表再无关系。
 * 此前这一步是直接 `select ums_address` 并把 {@code Address} 实体拿过去用（架构闸门 B1/B2），
 * 现在改为通过会员域的地址契约取值。
 *
 * <p>字段取舍：只带"组成完整地址 + 收件人信息"所需的部分；
 * 省/市/区的<b>编码</b>与创建/更新时间不在其中（下单不用它们）。
 * 完整地址字符串由调用方拼装——它是订单侧的展示口径，不属于地址域的数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddressSnapshotVO {

    private Long id;

    /** 归属会员：调用方可据此二次确认"这是我的地址"（契约本身也已做归属校验） */
    private Long memberId;

    private String receiverName;

    private String receiverPhone;

    private String provinceName;

    private String cityName;

    private String districtName;

    private String detail;
}
