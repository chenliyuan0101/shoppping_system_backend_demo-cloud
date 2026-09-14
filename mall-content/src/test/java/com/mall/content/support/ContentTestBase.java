package com.mall.content.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

/**
 * mall-content 真库集成测试基类。
 *
 * <p>与单体的 {@code MySqlTestBase} 相比刻意"薄"很多——这不是偷懒，而是抽服务之后
 * **每个服务只测自己那一份**：单体基类里那套"注册会员/建地址/下单支付/恢复库存基线"
 * 是跨域场景的脚手架，内容域一个都用不到（它连会员表都碰不到，见 §2.8 数据所有权矩阵）。
 * 后续服务照抄这个薄基类即可；什么时候真的需要跨服务断言，那应该是**契约测试**，
 * 而不是在某个服务的套件里装配另一个服务的库。
 */
@SpringBootTest(properties = "mall.internal.token=test-internal-token")
@AutoConfigureMockMvc
public abstract class ContentTestBase {

    /** 内部接口凭据头名（与单体的 InternalApiAuthInterceptor 逐字相同） */
    protected static final String TOKEN_HEADER = "X-Internal-Token";

    /**
     * 测试令牌。
     *
     * <p>⚠️ 必须写在 {@code @SpringBootTest(properties=...)} 上，不能只写进
     * {@code src/test/resources/application.properties}：**profile 专属文件优先级更高**，
     * {@code application-dev.yaml} 里的 dev 令牌会盖掉普通 properties 文件里的测试令牌，
     * 结果是"令牌正确"的用例收到 403（P2 实测踩过：无令牌用例照样 403，把问题掩盖了）。
     */
    protected static final String TOKEN = "test-internal-token";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** Redis 直连：仅供缓存相关断言/清理（业务代码禁止直接注入 RedisTemplate） */
    @Autowired
    protected StringRedisTemplate redisTemplate;

    /** 用例唯一后缀，避免标题/关键字冲突 */
    protected final String suffix = String.valueOf(System.nanoTime() % 1_000_000L);

    protected boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /** Redis 是否可用：涉及缓存的用例统一用 {@code assumeTrue(redisUp())} 守卫（没装 Redis 则跳过而非失败） */
    protected boolean redisUp() {
        try {
            redisTemplate.opsForValue().set("mall:test:ping", "pong", Duration.ofSeconds(10));
            return "pong".equals(redisTemplate.opsForValue().get("mall:test:ping"));
        } catch (Exception e) {
            return false;
        }
    }

    protected void setKey(String key, String value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    protected String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }
}
