package com.mall.review.client;

import com.mall.review.config.OutboundRestClientFactory;
import com.mall.review.support.ApiResponse;
import com.mall.review.support.dto.MemberNicknameVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * 会员域出站客户端：**只取昵称**，用于"提交评价时落昵称快照"。
 *
 * <h2>为什么这里还需要一次远程调用（P4 不是要把跨服务读全部消灭吗）</h2>
 * P4 消灭的是<b>读路径</b>的跨服务调用：评论列表展示昵称走本表的 {@code member_nickname}
 * 快照，一次都不远程。但"写入这个快照"必须有一个昵称来源，而评价域自己没有任何会员数据。
 * 两条路：
 * <ol>
 *   <li>{@code order.finished} 事件里带昵称——事件属于交易域，昵称属于会员域，
 *       塞进订单事件等于让 trade 依赖会员域（把环从 review 挪到 trade，不可取）；</li>
 *   <li><b>本次采用</b>：提交时按 member_id 取一次（本类），取不到就落空串。</li>
 * </ol>
 * 调用频率是"每次提交评价一次"（不是每次展示），且**失败绝不影响提交**（见 {@link #nickname(Long)}）。
 * 这条兜底路径记在 P4 报告里，属于"快照没有本地来源"时的显式取舍。
 *
 * <h2>P8-7 起：HTTP 调用改由声明式接口 {@link MemberSnapshotApi} 承担</h2>
 * 本类保留**域语义**三件事，其余（路径模板/动词/请求头/反序列化泛型）交给接口：
 * <ul>
 *   <li><b>连接装配</b>：{@code lb://} 走服务发现（{@code @LoadBalanced} 的 builder，其上已挂
 *       {@code OutboundHeadersInterceptor}）、{@code http://} 直连排障（同一套头由
 *       {@link OutboundRestClientFactory} 补齐），connect 300ms / read 1500ms 的硬超时在这里设；</li>
 *   <li><b>降级语义</b>：任何失败（不可达/超时/业务码非 0/昵称为空）→ **空串，绝不抛**；</li>
 *   <li><b>日志</b>：失败怎么记（一条 WARN 的措辞）由本类定，接口层不打日志。</li>
 * </ul>
 *
 * <h2>三条纪律</h2>
 * <ol>
 *   <li><b>硬超时</b>（connect 300ms / read 1500ms）：这是用户可感知的写路径，
 *       不能因为会员域慢就把"发表评价"卡住；</li>
 *   <li><b>绝不抛异常</b>：本方法的返回值就是"昵称，或空串"。昵称是展示用冗余字段，
 *       让它把一次成功的评价提交变成失败，是把"锦上添花"做成了"单点故障"
 *       （与改造前 {@code MemberQueryService.briefs} 失败时的表现也不同——那时连写都不写）；</li>
 *   <li><b>业务码非 0 也算失败</b>：user-center 用 {@code code}/{@code data:null} 表达"没有这个会员"，
 *       不能把它当成功昵称（那会写出一个空头像的会员名）。</li>
 * </ol>
 */
@Slf4j
@Component
public class MemberSnapshotClient {

    private final MemberSnapshotApi api;

    /**
     * 生产装配：服务发现版 builder + 统一的出站装配（超时/直连头）+ 原本的配置项。
     *
     * <p>显式 {@code @Autowired}：本类另有下面那个"直连/单测"用的构造器，两个构造器并存时
     * 必须指明 Spring 用哪一个（否则启动即失败）。
     */
    @Autowired
    public MemberSnapshotClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                                OutboundRestClientFactory restClients,
                                @Value("${mall.user-center.base-url:lb://mall-user-center}") String baseUrl,
                                @Value("${mall.user-center.connect-timeout-ms:300}") long connectTimeoutMs,
                                @Value("${mall.user-center.read-timeout-ms:1500}") long readTimeoutMs) {
        RestClient restClient = restClients.build(loadBalancedBuilder, baseUrl, connectTimeoutMs, readTimeoutMs);
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(MemberSnapshotApi.class);
        log.info("会员域客户端就绪: base-url={} connect-timeout={}ms read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 直连构造：<b>保留给"真客户端 + 一个必然连不上的地址"这类单测</b>
     * （{@code MemberSnapshotClientTest} 直接 {@code new} 的就是这个签名，它要证明的正是
     * "连接被拒/超时"被收敛成空串，换成 mock 恰好会把被测的那段代码换掉）。
     *
     * <p>内部走**同一个** {@link OutboundRestClientFactory}（用传入的令牌现造一个），
     * 于是单测与生产的出站装配（超时、{@code X-Internal-Token}）完全一致，不会"测的是另一套客户端"。
     */
    public MemberSnapshotClient(RestClient.Builder builder, String baseUrl, String internalToken,
                                long connectTimeoutMs, long readTimeoutMs) {
        this(builder, new OutboundRestClientFactory(internalToken), baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 取会员昵称快照；**任何失败都返回空串，绝不抛异常**（调用方承诺"不因它失败"）。
     *
     * @param memberId 会员 id
     * @return 昵称；取不到（不可达/超时/业务码非 0/昵称为空）时为 {@code ""}
     */
    public String nickname(Long memberId) {
        if (memberId == null) {
            return "";
        }
        try {
            ApiResponse<MemberNicknameVO> response = api.snapshot(memberId);
            if (response == null || response.getCode() != ApiResponse.SUCCESS || response.getData() == null) {
                log.warn("会员昵称快照不可用，评价的昵称快照落空串: memberId={} code={}",
                        memberId, response == null ? null : response.getCode());
                return "";
            }
            String nickname = response.getData().getNickname();
            return StringUtils.hasText(nickname) ? nickname : "";
        } catch (Exception e) {
            // 连接被拒 / 超时 / 反序列化失败 / 无可用实例 —— 全部收敛成"没有昵称可用"
            log.warn("取会员昵称失败（不影响评价提交，快照落空串）: memberId={} err={}", memberId, e.toString());
            return "";
        }
    }
}
