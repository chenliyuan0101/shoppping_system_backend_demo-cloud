package com.mall.trade.app;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * MyBatis-Plus 手动装配(不依赖 mybatis-plus-spring-boot-autoconfigure，
 * 因其在 Spring Boot 4 下自动配置未生效)。
 * 说明：@MapperScan 放在本配置类而非启动类上——
 *   - 全量上下文(@SpringBootTest / 应用启动)生效；
 *   - @WebMvcTest 等切片会过滤掉本 @Configuration，避免注册 Mapper 却无 SqlSessionFactory。
 *
 * <p>P2：{@code com.mall.trade.cms.mapper} 已随内容域搬去 {@code mall-content}
 * （连带 {@code cms_banner}/{@code cms_notice} 两张表一起进了 {@code mall_content} schema），
 * 因此这里少一个包。后续每抽走一个域都会少一个——这正是 D.5 里"等 P2 按服务自带组装根处理"的意思。
 *
 * <p>P3-4：{@code com.mall.trade.auth.mapper} 与 {@code com.mall.trade.ums.mapper} 里的
 * 会员/地址/购物车/收藏/足迹 mapper 都随代码删掉了——它们的表（{@code ums_member}/{@code ums_address}/
 * {@code ums_cart_item}/{@code ums_favorite}/{@code ums_footprint}）已经搬进
 * {@code mall-user-center}（schema {@code mall_user}）。
 *
 * <p>P3-5：最后那个 {@code com.mall.trade.ums.mapper}（{@code NotificationMapper}）也删了——
 * 站内消息的写入方（消费者）搬到了 {@code mall-user-center}，单体不再有任何 {@code ums_*} 的
 * 真实访问点，因此扫描包里已经没有 {@code ums}。{@code mall.ums_notification} 表本身还在，
 * 但只剩历史的存量行，等观察一个结算周期后按 {@code db/03-drop-from-mall.sql} 删。
 * 会员身份照常工作：见 {@code auth.support.MemberSession}（网关快路径 / 域契约回退）。
 *
 * <p><b>P5 步骤 C</b>：{@code com.mall.trade.sms.mapper} 也移出扫描包了——券的读写整体搬到
 * {@code mall-marketing}，单体侧 {@code CouponMapper}/{@code CouponMemberMapper} 已删除，
 * 下单与后台都改走 {@code MarketingClient}（HTTP）。这里不再留一个空包路径：
 * 扫描包里要是没有 mapper，留着它只会让人以为"单体还能查券表"。
 *
 * <p><b>P6-6（决策 D6）：{@code com.mall.trade.pms.mapper} 按同一条理由移出扫描包。</b>
 * 那 6 个 mapper（{@code SpuMapper}/{@code SpuDetailMapper}/{@code SkuMapper}/
 * {@code SkuStockLogMapper}/{@code CategoryMapper}/{@code BrandMapper}）连同它们依赖的
 * {@code pms.domain} 实体、{@code pms.support} 装配类、只被它们使用的 DTO 一并删除——
 * 单体侧不再有任何一段 SQL 指向旧库 {@code mall} 的 {@code pms_*} 表
 * （P6-4 起商品数据属主是 {@code mall-product}，单体只薄转发，见 {@code AdminProductServiceImpl}）。
 * <p>⚠️ 扫描包里去掉一个路径、留一个空目录，等于留一句"这里还能查到商品表"的暗示——
 * 所以 {@code pms/mapper} 目录本身也删了。留在 {@code pms} 包里的
 * {@code controller}/{@code service}/{@code dto} 是那 16 个后台端点的薄转发外壳与 4 个域契约接口，
 * 它们<b>不含任何数据访问</b>，也不需要 mapper 扫描（它们的移除属于 P7 / {@code mall-admin} BFF）。
 * <p>这条改动由 {@code architecture.ModuleBoundaryTest} 的 D9 规则守着：
 * 生产代码里再出现 {@code pms_*} 表名的字符串字面量（新 mapper/新 SQL）即失败。
 */
@Configuration
@MapperScan({"com.mall.trade.admin.mapper",
        "com.mall.trade.oms.mapper"})
public class MybatisPlusConfig {

    @Bean
    public MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        // MP 默认配置(下划线转驼峰等)；SQL 走 slf4j，由 logging.level.org.apache.ibatis=debug 控制是否打印
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setLogImpl(org.apache.ibatis.logging.slf4j.Slf4jImpl.class);
        factoryBean.setConfiguration(configuration);

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);
        return factoryBean;
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
