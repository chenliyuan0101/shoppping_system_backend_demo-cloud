package com.mall.review.client;

import com.mall.review.support.ApiResponse;
import com.mall.review.support.dto.MemberNicknameVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;

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

    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    /** user-center 的会员档案快照端点（P3 就已存在，本服务只是消费方） */
    private static final String SNAPSHOT_PATH = "/internal/v1/user/member/{id}/snapshot";

    private final RestClient restClient;
    private final String internalToken;

    public MemberSnapshotClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                                @Value("${mall.user-center.base-url:lb://mall-user-center}") String baseUrl,
                                @Value("${mall.internal.token:}") String internalToken,
                                @Value("${mall.user-center.connect-timeout-ms:300}") long connectTimeoutMs,
                                @Value("${mall.user-center.read-timeout-ms:1500}") long readTimeoutMs) {
        // 只有 lb:// 才需要服务发现版 builder；直连地址（本地排障/测试）用普通 builder
        RestClient.Builder builder = baseUrl.startsWith("lb://") ? loadBalancedBuilder : RestClient.builder();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalToken = internalToken;
        log.info("会员域客户端就绪: base-url={} connect-timeout={}ms read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
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
            ApiResponse<MemberNicknameVO> response = restClient.get()
                    .uri(SNAPSHOT_PATH, memberId)
                    .header(HEADER_INTERNAL_TOKEN, internalToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<MemberNicknameVO>>() {
                    });
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
