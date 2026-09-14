package com.mall.product.config;

import com.mall.product.support.MqTopology;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 商品索引同步拓扑的**装配（本服务 = 生产方版）**：只声明<b>交换机</b>。
 *
 * <h2>为什么只声明交换机，不声明队列/绑定</h2>
 * <ul>
 *   <li>交换机 {@code mall.pms.sync} 是<b>生产方的落点</b>：投递时它必须存在，所以本服务声明它
 *       （{@code DirectExchange, durable=true}，与 {@code mall-search}、过渡期单体<b>逐字同参</b>）；</li>
 *   <li>队列/绑定是<b>消费方的资产</b>：{@code mall.pms.es-sync}(+{@code .retry}/{@code .dlq}) 的
 *       DLX/DLK 参数由 {@code mall-search} 声明。本服务再声明一遍只会引入
 *       "两边参数不一致 ⇒ broker 拒绝声明（{@code PRECONDITION_FAILED}）"的风险，收益为零
 *       —— 参数一旦不一致，broker 上谁先声明谁生效，后来的那个直接把服务搞崩，P5 踩过。</li>
 *   <li>因此本服务的边界是"**只往交换机投**"：路由到哪个队列是搜索域的事（P6-5 规格 D1）。</li>
 * </ul>
 *
 * <p>⚠️ {@code mall.mq.enabled=false} ⇒ 不声明、不投递（{@code ProductSyncPublisher} 也会一律返回 false）
 * ⇒ 本模块的<b>全部真库用例</b>跑在"MQ 不可用"的路径上，与"单测不依赖外部 broker"这条纪律一致
 * （见 {@code ProductTestBase} 的 properties 注释）。
 */
@Configuration
@ConditionalOnProperty(name = "mall.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfig {

    @Bean
    public DirectExchange productSyncExchange() {
        return ExchangeBuilder.directExchange(MqTopology.SYNC_EXCHANGE).durable(true).build();
    }
}
