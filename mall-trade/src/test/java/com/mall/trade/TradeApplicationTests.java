package com.mall.trade;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * 原骨架的 contextLoads 需要连接真实 MySQL(dev 数据源)。
 * 注册/登录测试改为不依赖数据库的切片/单测：
 *  - MemberServiceTest   (Mockito 单测)
 *  - AuthControllerTest  (@WebMvcTest 切片)
 * 数据库就绪并需全链路冒烟时，可另行编写 @SpringBootTest 集成测试。
 */
@Disabled("全上下文启动需要本地 MySQL，已在单测/切片中覆盖注册登录")
class TradeApplicationTests {

    @Test
    void placeholder() {
        // 占位：避免空类
    }
}
