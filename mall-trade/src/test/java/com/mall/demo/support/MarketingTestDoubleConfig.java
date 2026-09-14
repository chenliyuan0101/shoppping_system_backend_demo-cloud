package com.mall.demo.support;

import com.mall.demo.common.contract.CouponCommandService;
import com.mall.demo.common.contract.CouponQueryService;
import com.mall.demo.common.dto.CouponBriefVO;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * <b>测试期的"营销域券替身"（全测试套件级）</b>——券契约在测试进程里的默认实现。
 *
 * <h2>为什么必须是"全测试级"，而不是只在券的套件里 mock</h2>
 * P5 步骤 C 之后券契约是**跨进程**的（{@code MarketingClient} → {@code mall-marketing}），
 * 而 {@code OrderServiceImpl.preview} 会**无条件**取一次"本单可用的券"
 * （{@code usableCoupons}，结算页的券下拉框）——也就是说**任何走 {@code /api/order/preview}
 * 的套件**都会碰到券契约。只在券的套件里 mock 的话，别的套件（如 {@code OrderFlowMySqlTest}）
 * 会在"本机没起 marketing"时直接 500，表现为"改了无关代码却把别的套件打红"。
 *
 * <p>本类给出**确定性的默认**，保证"CI 里没有 marketing 进程时整套回归可跑"：
 * <ul>
 *   <li>{@code usableCoupons(...)} → 空列表（预览响应里 {@code coupons: []}）；</li>
 *   <li>{@code discountFor(...)} → {@code 0L}（不用券）；</li>
 *   <li>{@code lock(...)} / {@code unlock(...)} → {@code true}（确定性：带券下单也能跑完）。</li>
 * </ul>
 *
 * <p><b>⚠️ 这套默认值的边界（必须知道）</b>：{@code lock→true} 意味着"带券下单"在没有 mock 的套件里
 * 也会成功——它**不能**用来证明券的任何行为。因此**券相关的套件必须用 {@code @MockitoBean}
 * 覆盖成具体行为并断言交互**（见 {@code oms/CouponMySqlTest}：断言 {@code lock} 收到的
 * {@code orderNo} 就是本单、成功路径不调 {@code unlock}、{@code locked=false} → 409；
 * 另见 {@code admin/AdminCouponMySqlTest} 覆盖 {@code MarketingClient} 断言转发与文案透传）。
 * 券的规则本体在营销域的真库套件里（{@code mall-marketing: CouponQueryMySqlTest /
 * CouponTriStateMySqlTest / CouponMemberApiMySqlTest / InternalMarketingAdminCouponApiMySqlTest}）。
 *
 * <h2>接线方式（与 {@link UserCenterTestDoubleConfig} 完全同一套）</h2>
 * 通过 {@code src/test/resources/META-INF/spring/…AutoConfiguration.imports} 自动生效，
 * 因此**每个 {@code @SpringBootTest} 上下文**都拿得到（不依赖测试类是否继承 {@code MySqlTestBase}）；
 * 生产实现 {@code app.MarketingRemoteConfig} 因为测试属性
 * {@code mall.marketing.remote=false} 而退让（{@code @ConditionalOnProperty}），
 * 本类的 {@code @ConditionalOnMissingBean} 才注册得进来——这正是 user-center 替身用了两轮的做法。
 *
 * <h2>⚠️ 必读的坑：远程接线配置**必须**带条件注解，否则本替身会被"静默顶掉"（P6/P7 每个新服务都会踩）</h2>
 * 症状：某个**与券无关**的套件跑红，报的是远程调用失败（本工程里是 {@code /api/order/preview}
 * 500「系统繁忙」），而券自己的套件（用 {@code @MockitoBean} 的）**却是绿的**——极具误导性的组合。成因：
 * <ol>
 *   <li>{@code @AutoConfiguration} 的处理顺序**在用户配置之后**；</li>
 *   <li>若生产接线是一个**无条件**的 {@code @Configuration}（{@code app} 包被组件扫描），
 *       它的 {@code couponQueryService} bean 已经存在；</li>
 *   <li>于是本类的 {@code @Bean @ConditionalOnMissingBean} **退让、什么都不提供**，且
 *       **不报错、不打 warning、不启动失败**——测试期照样走真客户端 → 打不到 marketing → 500；</li>
 *   <li>反证（当时实测的对照）：{@code oms.CouponMySqlTest} 的 5 个用例**全绿**，
 *       因为它用 {@code @MockitoBean} 按**类型强制替换**，不受 {@code @ConditionalOnMissingBean} 影响。</li>
 * </ol>
 * ⇒ **规则**：每个服务"跨进程契约的远程接线配置"都要带
 * {@code @ConditionalOnProperty(name="mall.<svc>.remote", havingValue="true", matchIfMissing=true)}，
 * 并在 {@code MySqlTestBase} 的 {@code @TestPropertySource} 里显式关掉
 * （本工程：{@code mall.user-center.remote=false}、{@code mall.marketing.remote=false}）。
 * {@code matchIfMissing=true} 是**生产正确性**的一部分：不传属性时就是"远程"，
 * 因此不存在"忘了传开关就坏"的风险；该属性只用于测试期显式关闭。
 * 替身是否真的在生效由 {@code MarketingRemoteWiringTest} 直接断言（防这条坑静默回归）。
 *
 * <h2>这条坑的<b>可执行证据</b>：守卫类自己第一版就是红的（别把它当"多写的一个用例"）</h2>
 * {@code MarketingRemoteWiringTest} 第一版**没有 {@code extends MySqlTestBase}**，于是：
 * <ol>
 *   <li>它拿不到基类 {@code @TestPropertySource} 里的 {@code mall.marketing.remote=false}；</li>
 *   <li>{@code app.MarketingRemoteConfig} 的 {@code matchIfMissing=true} 让**远程实现先注册**；</li>
 *   <li>本替身的 {@code @ConditionalOnMissingBean} 静默退让 ⇒ 断言"注入的契约 bean 是替身"**直接红**。</li>
 * </ol>
 * ⇒ 那个红灯**就是**上面这条坑的证据（症状与"券无关的套件 500"不同：这里是守卫类自己报"契约 bean 不是替身"）。
 * 因此**守卫类必须继承 {@code MySqlTestBase}**——它要断言的是"替身确实赢了"，而"赢"的前提正是
 * 基类注入的那条属性。
 *
 * <p>由此也读准上面 §接线方式 那句"每个 {@code @SpringBootTest} 上下文都拿得到"：
 * 替身的**注册**是全局的（AutoConfiguration），但**是否生效**取决于 {@code mall.marketing.remote=false}
 * 有没有被注入——不继承基类、又自带 {@code @SpringBootTest(properties=…)} 的套件会静默走真远程。
 * P6/P7 给每个新服务写替身时，请把"守卫类继承测试基类"当成硬要求照抄。
 *
 * <p>补充一条边界（2026-09-14 核实）：本工程**没有**任何子类自己声明
 * {@code @TestPropertySource}（7 个自带属性的套件用的都是 {@code @SpringBootTest(properties=…)}，
 * 它是**叠加**的内联属性，不会移除基类那份），因此基类的 {@code mall.marketing.remote=false}
 * 不会被这些套件丢掉；同一属性另有一份写在 {@code src/test/resources/application.properties} 里做双保险。
 * （对比：{@code mall-marketing}/{@code mall-review} 把令牌写在基类的 {@code @SpringBootTest(properties=…)} 里，
 * 那里的子类声明 {@code @SpringBootTest} 会**整个替换**基类那份 ⇒ 必须重述令牌，见 review 的
 * {@code OrderFinishedMqMySqlTest} 与 marketing 的 {@code OrderClosedMqMySqlTest} 的注释。）
 */
@AutoConfiguration
public class MarketingTestDoubleConfig {

    @Bean
    @ConditionalOnMissingBean
    public CouponQueryService couponQueryService() {
        return new NoCouponQuery();
    }

    @Bean
    @ConditionalOnMissingBean
    public CouponCommandService couponCommandService() {
        return new AlwaysOkCommand();
    }

    /** 查询替身：没有可用券、不用券不抵扣 */
    public static class NoCouponQuery implements CouponQueryService {

        @Override
        public long discountFor(Long memberId, Long couponMemberId, long goodsTotal) {
            return 0L;
        }

        @Override
        public List<CouponBriefVO> usableCoupons(Long memberId, long goodsTotal) {
            return List.of();
        }
    }

    /**
     * 命令替身：锁定/核销/解锁都返回 true。
     *
     * <p>刻意选 true 而不是 false：默认值要让**与券无关**的套件（带券下单/支付/退款的冒烟路径）能跑完；
     * 若默认 false，那些套件会以 409 失败，反而把"券替身没配对"伪装成业务冲突。
     * 代价是"没 mock 就断言不了券行为"，已在类注释里写明，并由券套件用 {@code @MockitoBean} 覆盖。
     *
     * <p>⚠️ {@link #use} 是 **P5 步骤 E** 加进契约的（支付成功时核销 {@code LOCKED → USED}）：
     * 契约一改，这个替身就必须跟着实现，否则**整个 demo 的 testCompile 挂掉**
     * （javac 只说"未覆盖抽象方法"，看不出是"别人的契约变了"——这也是"一个仓库两个施工者"的典型代价）。
     */
    public static class AlwaysOkCommand implements CouponCommandService {

        @Override
        public boolean lock(Long memberId, Long couponMemberId, String orderNo) {
            return true;
        }

        @Override
        public boolean use(Long memberId, Long couponMemberId, String orderNo) {
            return true;
        }

        @Override
        public boolean unlock(Long memberId, Long couponMemberId, String orderNo) {
            return true;
        }
    }
}