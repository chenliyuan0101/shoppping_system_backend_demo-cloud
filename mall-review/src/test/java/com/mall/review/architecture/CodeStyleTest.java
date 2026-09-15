package com.mall.review.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 《微服务代码规范.md》的<b>可执行版本</b>：命名后缀与包职责。
 *
 * <p>为什么每个服务各有一份：9 个模块是 9 个独立 Maven 工程（没有聚合 pom），
 * CI 逐个模块跑 {@code mvn test} ⇒ 规则必须落在各自模块里才会被执行。
 * 三份"契约面"（{@code ApiResponse} / {@code BusinessException} / {@code GlobalExceptionHandler}）
 * <b>刻意保持各服务自持副本</b>（C1 逐字同构），所以本类只查"名字与层次"，不查"是否重复"。
 *
 * <p>两条经过实测修正的写法（第一版踩过，别改回去）：
 * <ul>
 *   <li>必须**排除嵌套类与匿名类**：Lombok 的 {@code @Builder} 会生成 {@code XxxVO\}、
 *       匿名内部类会生成 {@code WebConfig\} —— 它们随宿主类走，不该被"后缀白名单"判违规；</li>
 *   <li>{@code service.impl} 的口径是"名字以 Service 结尾"（{@code *ServiceImpl} 或
 *       {@code RemoteProductSearchService} 这类**策略实现**按能力命名，语义比强行加 Impl 更清楚）。</li>
 * </ul>
 * 规则只在**与现状核对过**的前提下写（先跑一遍、确认不红，再纳入）；
 * {@code allowEmptyShould(true)} 是为了让"本服务没有这个包"（如网关没有 dto/mapper）也算通过。
 */
@DisplayName("代码规范：命名后缀与包职责")
class CodeStyleTest {

    /** 本服务代码根包（每个服务只改这一行） */
    private static final String BASE = "com.mall.review";

    /** 只看**顶层类**：嵌套类（Lombok @Builder / record / 匿名内部类）跟随宿主，不参与后缀判定 */
    private static final DescribedPredicate<JavaClass> TOP_LEVEL =
            new DescribedPredicate<>("顶层类（非嵌套/匿名类）") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return javaClass.getEnclosingClass().isEmpty();
                }
            };

    private static JavaClasses types;

    @BeforeAll
    static void importClasses() {
        types = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    @DisplayName("dto 包：类名后缀必须在白名单内")
    void dtoNaming() {
        classes().that().resideInAPackage("..dto..").and(TOP_LEVEL)
                .should().haveSimpleNameEndingWith("VO")
                .orShould().haveSimpleNameEndingWith("Request")
                .orShould().haveSimpleNameEndingWith("Response")
                .orShould().haveSimpleNameEndingWith("Query")
                .orShould().haveSimpleNameEndingWith("Message")
                .orShould().haveSimpleNameEndingWith("Result")
                .orShould().haveSimpleNameEndingWith("Doc")
                .orShould().haveSimpleNameEndingWith("Page")
                .orShould().haveSimpleNameEndingWith("Info")
                .orShould().haveSimpleNameEndingWith("Aggregate")
                .orShould().haveSimpleNameEndingWith("Data")
                .orShould().haveSimpleNameEndingWith("Node")
                .orShould().haveSimpleNameEndingWith("Snapshot")
                .orShould().haveSimpleNameEndingWith("Item")
                .orShould().haveSimpleNameEndingWith("Brief")
                .allowEmptyShould(true)
                .check(types);
    }

    @Test
    @DisplayName("client 包：只允许声明式接口 *Api / 域语义 *Client / 出站装配 *Factory|*Interceptor / 客户端专属 *Exception")
    void clientNaming() {
        classes().that().resideInAPackage("..client..").and(TOP_LEVEL)
                .should().haveSimpleNameEndingWith("Api")
                .orShould().haveSimpleNameEndingWith("Client")
                .orShould().haveSimpleNameEndingWith("Factory")
                .orShould().haveSimpleNameEndingWith("Interceptor")
                .orShould().haveSimpleNameEndingWith("Exception")
                .allowEmptyShould(true)
                .check(types);
    }

    @Test
    @DisplayName("controller 包：只放 *Controller")
    void controllerNaming() {
        classes().that().resideInAPackage("..controller..").and(TOP_LEVEL)
                .should().haveSimpleNameEndingWith("Controller")
                .allowEmptyShould(true)
                .check(types);
    }

    @Test
    @DisplayName("mapper 包：只放 *Mapper")
    void mapperNaming() {
        classes().that().resideInAPackage("..mapper..").and(TOP_LEVEL)
                .should().haveSimpleNameEndingWith("Mapper")
                .allowEmptyShould(true)
                .check(types);
    }

    @Test
    @DisplayName("service 层：接口 *Service、实现 *ServiceImpl（策略实现允许 *Service）")
    void serviceNaming() {
        classes().that().resideInAPackage("..service").and().resideOutsideOfPackage("..service.impl..").and(TOP_LEVEL)
                .should().haveSimpleNameEndingWith("Service")
                .allowEmptyShould(true)
                .check(types);
        classes().that().resideInAPackage("..service.impl..").and(TOP_LEVEL)
                .should().haveSimpleNameEndingWith("ServiceImpl")
                .orShould().haveSimpleNameEndingWith("Service")
                .allowEmptyShould(true)
                .check(types);
    }

    @Test
    @DisplayName("config 包：只放装配/拦截器/切面/解析器 *Config|*Interceptor|*Aspect|*Resolver|*Filter|*Executor|*Verifier|*Factory|*Properties")
    void configNaming() {
        classes().that().resideInAPackage("..config..").and(TOP_LEVEL)
                .should().haveSimpleNameEndingWith("Config")
                .orShould().haveSimpleNameEndingWith("Interceptor")
                .orShould().haveSimpleNameEndingWith("Aspect")
                .orShould().haveSimpleNameEndingWith("Resolver")
                .orShould().haveSimpleNameEndingWith("Filter")
                .orShould().haveSimpleNameEndingWith("Executor")
                .orShould().haveSimpleNameEndingWith("Verifier")
                .orShould().haveSimpleNameEndingWith("Factory")
                .orShould().haveSimpleNameEndingWith("Properties")
                .allowEmptyShould(true)
                .check(types);
    }

    @Test
    @DisplayName("task 包：只放 *Task")
    void taskNaming() {
        classes().that().resideInAPackage("..task..").and(TOP_LEVEL)
                .should().haveSimpleNameEndingWith("Task")
                .allowEmptyShould(true)
                .check(types);
    }

    @Test
    @DisplayName("mq 包：只放 *Consumer / *Publisher / *Message / *Config / *Topology / *Messages")
    void mqNaming() {
        classes().that().resideInAPackage("..mq..").and(TOP_LEVEL)
                .should().haveSimpleNameEndingWith("Consumer")
                .orShould().haveSimpleNameEndingWith("Publisher")
                .orShould().haveSimpleNameEndingWith("Message")
                .orShould().haveSimpleNameEndingWith("Config")
                .orShould().haveSimpleNameEndingWith("Topology")
                .orShould().haveSimpleNameEndingWith("Messages")
                .allowEmptyShould(true)
                .check(types);
    }

    @Test
    @DisplayName("domain（持久化实体）不得依赖入站/服务/客户端层")
    void domainDoesNotDependOnOuterLayers() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..controller..", "..internal..", "..client..", "..service..")
                .allowEmptyShould(true)
                .check(types);
    }

    @Test
    @DisplayName("support 不得依赖入站/服务/客户端层（support 只放无业务语义的工具与常量）")
    void supportDoesNotDependOnOuterLayers() {
        noClasses().that().resideInAPackage("..support..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..controller..", "..internal..", "..client..", "..service..")
                .allowEmptyShould(true)
                .check(types);
    }
}