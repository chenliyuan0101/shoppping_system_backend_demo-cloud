package com.mall.review.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.mall.common.support.MemberId;

/**
 * {@link MemberSnapshotClient} 的降级契约：<b>取昵称失败必须是"空串"，绝不是异常</b>。
 *
 * <p>这条约定了整个提交链路的一个关键性质：昵称是展示用冗余字段，
 * 它的来源（会员域）不可用时，"发表评价"仍然必须成功——否则会员域的一次抖动
 * 就会变成"全站评价发不出去"（而且是在一个**写**路径上，用户的操作白做了）。
 *
 * <p>用<b>真客户端 + 一个必然连不上的地址</b>来测（而不是 mock）：要证明的正是
 * "连接被拒/超时/无可用实例"这些现实失败形态都被收敛成空串，
 * mock 掉 HTTP 层恰好会把被测的那段代码换成假的。
 * 端口 1 在本机不会有监听者，connect 立刻被拒（配置里还有 300ms 的硬超时兜底）。
 */
class MemberSnapshotClientTest {

    private static final String DEAD_BASE_URL = "http://127.0.0.1:1";

    private MemberSnapshotClient client(String baseUrl) {
        return new MemberSnapshotClient(RestClient.builder(), baseUrl, "test-token", 300, 500);
    }

    @Test
    @DisplayName("[降级] 会员域连不上 → 返回空串（不抛异常），提交评价因此不会失败")
    void unreachableMemberService_returnsEmptyNickname() {
        assertEquals("", client(DEAD_BASE_URL).nickname(1L),
                "会员域不可用时必须返回空串：抛异常会让一次成功的评价提交变成失败");
    }

    @Test
    @DisplayName("[降级] memberId 为 null → 空串（不发请求）")
    void nullMemberId_returnsEmptyNickname() {
        assertEquals("", client(DEAD_BASE_URL).nickname(null));
    }
}
