package com.mall.usercenter.support;

import com.mall.usercenter.support.dto.MemberStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import com.mall.common.support.CacheService;
import com.mall.common.support.MemberId;

/**
 * 成员状态缓存（§4.4 ②/③）：{@code mall:cache:member:status:{memberId}} → {@code {memberId,nickname,status}}。
 *
 * <p><b>它解决什么问题</b>：改造前每个带 {@code @MemberId} 的请求都要 {@code selectById(ums_member)}
 * 才能判断"会员还在不在、有没有被禁用"。这个缓存让热路径**完全不碰库**：
 * 网关负责验签与令牌版本，状态由这份缓存回答。
 *
 * <p><b>本服务是这张表的属主</b>，因此也是这份缓存的**唯一写入方**：
 * 状态/资料变更时删除（只删不写——下次读取时按需重建，避免"写了一份旧的进去"这类难查的脏缓存）。
 * 网关与其它业务服务只读它。
 *
 * <p>TTL 10 分钟（与 §4.4 ② 一致）：即使某次失效通知丢了，脏数据也活不过 10 分钟；
 * 而真正的"立即失效"仍由令牌版本号保证（版本不对 → 网关直接 401），两者是不同层次的保险。
 */
@Component
@RequiredArgsConstructor
public class MemberStatusCache {

    /** 与 §4.4 ② 一致：10 分钟 */
    private static final Duration TTL = Duration.ofMinutes(10);

    private final CacheService cacheService;

    /** 读缓存；未命中返回 {@code null}（Redis 不可用时也返回 null，由调用方回落到查库） */
    public MemberStatusVO get(long memberId) {
        return cacheService.getJson(CacheKeys.memberStatus(memberId), MemberStatusVO.class);
    }

    /** 回填（会员注册/登录/读取后调用；Redis 不可用时静默跳过） */
    public void put(long memberId, String nickname, Integer status) {
        cacheService.setJson(CacheKeys.memberStatus(memberId), new MemberStatusVO(memberId, nickname, status), TTL);
    }

    /**
     * 删除（会员资料/状态变更后调用）。
     *
     * <p>注意：**它不负责"立即踢下线"**。禁用会员的立即生效靠
     * {@link TokenVersionService#bump(String, long)}（网关比对版本号后直接 401），
     * 本缓存只是状态读取的加速层。
     */
    public void evict(long memberId) {
        cacheService.delete(CacheKeys.memberStatus(memberId));
    }
}
