package com.mall.demo.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P0「边界冻结」的核心闸门：<b>跨域依赖棘轮</b>。
 *
 * <p>背景见《微服务改造方案.md》§5 P0 与附录 D：当前单体里存在大量"跨域直连"——
 * 一个域直接 import 另一个域的 {@code mapper}/{@code domain}（等于直接读写别人的表），
 * 以及借用别人的 {@code dto}/{@code support}、依赖组装根 {@code app} 包。
 * 这些依赖在拆微服务时会变成"分布式单体"，必须在拆之前清零。
 *
 * <p><b>工作方式（棘轮机制）</b>
 * <ol>
 *   <li>分别采集两层耦合：<b>字节码层</b>（ArchUnit 读 {@code target/classes}）
 *       与<b>源码 import 层</b>（扫描 {@code src/main/java} 的 import 语句）；</li>
 *   <li>两层按 {@link Rule} 归类后<b>合并</b>（同一 {@code origin -> target} 只记一条，
 *       但报告里区分"仅字节码可见/仅 import 可见/两者都有"）；</li>
 *   <li>与基线 {@code src/test/resources/archunit/module-boundary-baseline.txt} 比对：
 *       <ul>
 *         <li>出现基线里没有的条目 → <b>失败</b>（CI 阻断新增的跨域依赖）；</li>
 *         <li>基线里有、但现在已不违规的条目 → <b>同样失败</b>（强制删行，基线只减不增、不会烂掉）。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>为什么要两层（这不是过度设计，是实测踩出来的）</b>：
 * <ul>
 *   <li><b>字节码层</b>看得见"运行期真实耦合"：调用别人的 mapper 方法、把别人的实体当字段/参数类型。</li>
 *   <li><b>源码 import 层</b>才知道"编译期耦合"。因为 javac 会把 {@code static final} 常量
 *       <b>内联</b>进调用方字节码：{@code OrderStatus.WAIT_PAY}（int）、{@code MqTopology.SYNC_QUEUE}（String）
 *       这类引用在 class 文件里<b>根本不存在</b>，字节码层完全看不到。
 *       实测：{@code admin}/{@code oms}/{@code pms} 共 8 个类 import 了 {@code app.RabbitMqConfig}，
 *       只做字节码层时该规则假报 0 条。</li>
 * </ul>
 * 两层都看，才既能抓住"读写别人数据"，也不漏"借别人的常量词汇表"。</p>
 *
 * <p><b>为什么不用 {@code FreezingArchRule}</b>：它的冻结存储把 {@code (File.java:行号)} 写进条目，
 * 而 P0 阶段正要大量改写这些文件——行号一移动就会误报"新增违规 + 旧违规消失"。
 * 这里改用<b>只由类名构成的稳定键</b>（{@code origin -> target}），因此可以放心重构。</p>
 *
 * <p>重新生成基线（确认过 diff 再提交）：
 * <pre>mvn test -Dtest=ModuleBoundaryTest -Darchunit.baseline.write=true</pre>
 *
 * <p><b>P0 完成的判定标准：基线文件里只剩注释头，没有任何条目。</b>
 */
@DisplayName("P0 模块边界约束（跨域依赖棘轮）")
class ModuleBoundaryTest {

    /** 代码根包 */
    private static final String ROOT = "com.mall.demo";

    /** 业务域：P0 之后每个都会成为独立服务（见方案 §2.2） */
    private static final Set<String> BUSINESS = Set.of("admin", "auth", "cms", "oms", "pms", "sms", "ums");

    /** 全部模块 = 业务域 + 共享内核(common) + 组装根(app) + 内部接口适配层(internal) */
    private static final Set<String> MODULES =
            Set.of("admin", "app", "auth", "cms", "common", "internal", "oms", "pms", "sms", "ums");

    /** 包结构里可识别的"层"（第二段）。其余第二段一律是类名，不算层。 */
    private static final Set<String> LAYERS =
            Set.of("controller", "domain", "dto", "mapper", "service", "support", "mq", "task", "constant", "config");

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    /** 采集源码 import（含 static import；静态导入的成员名不影响模块/层判定） */
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?(" + ROOT.replace(".", "\\.") + "\\.[A-Za-z0-9_.]+)\\s*;",
                    Pattern.MULTILINE);

    private static final Path BASELINE = Path.of("src/test/resources/archunit/module-boundary-baseline.txt");

    /**
     * P6-6 / D9：<b>已冻结的 6 张旧商品表</b>。
     *
     * <p>P6-4 起商品数据的属主是 {@code mall-product}（schema {@code mall_product}），
     * 单体侧的本地实现、mapper、实体、装配类已在 P6-6 删除，旧库 {@code mall} 里的这 6 张表
     * 只剩冻结存量（架构师随后删表）。这条规则的作用是：<b>让"删表之后不可能再有人读它们"成为可执行的断言</b>，
     * 而不是靠一次性的 grep。
     */
    private static final List<String> FROZEN_PMS_TABLES = List.of(
            "pms_spu", "pms_spu_detail", "pms_sku", "pms_sku_stock_log", "pms_category", "pms_brand");

    /** 按长度降序：{@code pms_spu_detail} 要先于 {@code pms_spu} 命中（前缀关系），这样报告里的表名才是最长匹配 */
    private static final List<String> FROZEN_PMS_TABLES_LONGEST_FIRST = FROZEN_PMS_TABLES.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    private static final String HEADER = """
            # P0 模块边界基线（ArchUnit 棘轮）—— 依据《微服务改造方案.md》§5 P0 / 附录 D
            #
            # 格式：<规则ID>|<origin 全限定类名> -> <target 全限定类名>
            # 说明：键只由类名构成，不含行号，因此重构（挪方法/挪行/重排 import）不会造成基线噪声。
            #       条目来自"字节码层 + 源码 import 层"的合并（任一层可见即记一条）。
            # 语义：这是"存量跨域依赖"的显式白名单。
            #       · 出现基线里没有的跨域依赖 → ModuleBoundaryTest 失败（CI 阻断新增耦合）
            #       · 基线里已有但当前不再违规的条目 → 测试同样失败（强制删行，基线只减不增）
            #       · P0 完成 = 本文件只剩这段注释，没有任何条目
            # 重新生成：mvn test -Dtest=ModuleBoundaryTest -Darchunit.baseline.write=true
            #
            """;

    /**
     * 被禁止的跨域依赖类别（7 条，构成一个<b>划分</b>：每条违规恰好归一类）。
     *
     * <p>两点约定：
     * <ul>
     *   <li>{@link #B1}~{@link #B4} 的语义是"借用<b>其它业务域</b>的层"，
     *       因此要求 origin 与 target 都是业务域——依赖共享内核 {@code common}
     *       （如 {@code common.MqMessages}、未来的 {@code common.dto.StatusRequest}）是<b>合法</b>的；</li>
     *   <li>各规则必须互斥，否则统计会互相掩盖（{@link #enforceModuleBoundaries()} 有自洽断言守着）。</li>
     * </ul>
     */
    private enum Rule {

        /** 直连别人的表：拆服务后就是"跨库写"，最严重的一类（方案 §1.3 表 #1~#4） */
        B1("跨域依赖其它业务域的 mapper（等价于读写别人的表）",
                d -> bothBusiness(d) && "mapper".equals(d.targetLayer())),

        /** 直连别人的实体：Entity 是持久层类型，不该成为域间契约 */
        B2("跨域依赖其它业务域的 domain（持久化实体不该作为域间契约）",
                d -> bothBusiness(d) && "domain".equals(d.targetLayer())),

        /** 借用别人的 DTO：合法但会让 DTO 变更牵动多个域，应上移为共享契约 DTO */
        B3("跨域借用其它业务域的 dto（应上移为共享契约 DTO）",
                d -> bothBusiness(d) && "dto".equals(d.targetLayer())),

        /** 别人的 support：含枚举/常量（借词汇表）与业务规则类（真实泄漏） */
        B4("跨域依赖其它业务域的 support（枚举常量/业务规则）",
                d -> bothBusiness(d) && "support".equals(d.targetLayer())),

        /**
         * 组装根（{@code app} 包）只能依赖业务域，不能被业务域反向依赖。
         * 否则"每个服务各自拥有组装根"（方案 §6.1）就不成立：拆服务时组装根会跟着业务代码走。
         */
        B5("依赖组装根 app 包（组装根只应被启动类引用，不得被业务域反向依赖）",
                d -> "app".equals(d.targetModule()) && !"app".equals(d.originModule())),

        /** 共享内核不能反过来依赖业务域 */
        B6("共享内核 common 依赖业务域（内核必须无业务语义）",
                d -> "common".equals(d.originModule()) && BUSINESS.contains(d.targetModule())),

        /** 实现类不得被外部引用（只能通过接口） */
        B7("依赖其它业务域的 service.impl（实现类不得被外部引用）",
                d -> bothBusiness(d) && d.targetClass().contains(".service.impl.")),

        /**
         * 内部接口适配层（{@code internal} 包）是"给将来的跨进程调用方"用的 HTTP 门面。
         * 同进程的域间协作必须走域服务接口（依赖内聚、可测、无网络开销）；
         * 依赖 internal 包等于把自己的调用硬绕成一次 HTTP——是拆服务时最典型的误用。
         */
        B8("业务域依赖 internal 内部接口包（域间协作应走域服务接口，不是 HTTP 适配层）",
                d -> BUSINESS.contains(d.originModule()) && "internal".equals(d.targetModule())),

        /**
         * P4 目标态规则（按棘轮做法先给基线，拆完自然烧穿）。
         *
         * <p><b>为什么必须加这条</b>：方案把"pms ↔ oms / pms → auth 的环"写成 P4 的产出，
         * 但盘点实测发现 B1–B8 <b>根本不禁止</b> pms → oms（pms 依赖的是 oms 的 service 接口，
         * 不命中任何既有规则），基线此刻为空 —— 所以"闸门 0 违规"<b>不能</b>当作"环已断"的证据。
         * 这条规则把那句话变成可验证的：当前只剩评价链路的 1 处依赖，
         * P4 把评价域搬走后归零，基线随之清空。
         */
        B9("商品域 pms 依赖交易域 oms 或会员域 auth（P4 目标态：评价域拆出后应归零）",
                d -> "pms".equals(d.originModule())
                        && ("oms".equals(d.targetModule()) || "auth".equals(d.targetModule())));

        /** origin 与 target 都是业务域 → 这才是真正的"跨业务域"依赖（依赖 common/app 不算） */
        private static boolean bothBusiness(Dep dep) {
            return BUSINESS.contains(dep.originModule()) && BUSINESS.contains(dep.targetModule());
        }

        private final String title;
        private final Predicate<Dep> predicate;

        Rule(String title, Predicate<Dep> predicate) {
            this.title = title;
            this.predicate = predicate;
        }

        String title() {
            return title;
        }
    }

    /** 一条跨模块依赖（键只含类名，重构安全） */
    private record Dep(String originModule,
                       String originClass,
                       String targetModule,
                       String targetClass,
                       String targetLayer) {

        /** 基线文件里的行内容（不含规则ID前缀） */
        String key() {
            return originClass + " -> " + targetClass;
        }

        String modulePair() {
            return originModule + "→" + targetModule;
        }

        /** 域服务接口（不含 impl）之间的跨域依赖 —— P0 的目标形态 */
        boolean isServiceInterfaceDependency() {
            return targetClass.contains(".service.")
                    && !targetClass.contains(".service.impl.");
        }
    }

    // ==================================================================
    // 测试本体
    // ==================================================================

    @Test
    @DisplayName("跨域依赖不得新增，且基线必须随修复收缩")
    void enforceModuleBoundaries() throws IOException {
        List<Dep> bytecodeDeps = collectBytecodeDependencies();
        List<Dep> importDeps = collectSourceImports();
        Set<Dep> unionAll = new TreeSet<>(Comparator.comparing(Dep::key));
        unionAll.addAll(bytecodeDeps);
        unionAll.addAll(importDeps);
        List<Dep> allDeps = new ArrayList<>(unionAll);

        Map<String, Set<String>> byRule = new TreeMap<>();
        Map<String, int[]> layerStats = new TreeMap<>();   // {仅字节码, 仅import, 两者都有}
        for (Rule rule : Rule.values()) {
            Set<String> bc = keys(bytecodeDeps, rule);
            Set<String> im = keys(importDeps, rule);
            Set<String> merged = new TreeSet<>(bc);
            merged.addAll(im);
            byRule.put(rule.name(), merged);
            layerStats.put(rule.name(), new int[]{
                    count(bc, im, Strategy.BC_ONLY),
                    count(bc, im, Strategy.IMPORT_ONLY),
                    count(bc, im, Strategy.BOTH)});
        }

        Set<String> currentLines = flatten(byRule);

        // 规则划分自洽性：每条违规恰好归属一类。
        long matched = allDeps.stream()
                .filter(dep -> Arrays.stream(Rule.values()).anyMatch(r -> r.predicate.test(dep)))
                .count();
        assertTrue(matched == currentLines.size(),
                "规则划分不自洽：命中规则的依赖 %d 条，按规则归集后却得到 %d 条（谓词存在重叠或漏项）"
                        .formatted(matched, currentLines.size()));

        printReport(byRule, layerStats, allDeps, bytecodeDeps, importDeps);

        boolean regenerate = Boolean.getBoolean("archunit.baseline.write");
        if (regenerate || !Files.exists(BASELINE)) {
            writeBaseline(currentLines);
            if (!regenerate) {
                fail("""
                        基线文件此前不存在，已根据当前代码生成：%s
                        共 %d 条存量跨域依赖。请复核内容后提交该文件，然后重新执行测试。
                        （这是首次运行的一次性行为；若基线被误删，用 -Darchunit.baseline.write=true 显式重建）"""
                        .formatted(BASELINE, currentLines.size()));
            }
            return;
        }

        Set<String> baselineLines = readBaseline();

        Set<String> added = new TreeSet<>(currentLines);
        added.removeAll(baselineLines);

        Set<String> fixed = new TreeSet<>(baselineLines);
        fixed.removeAll(currentLines);

        if (!added.isEmpty()) {
            fail("""
                    ❌ 检测到 %d 条【新增】的跨域依赖，请改为调用对方的域服务接口（方案 §5 P0 任务 2）：
                    %s
                    （如确属存量平移，请审查后用 -Darchunit.baseline.write=true 重建基线）"""
                    .formatted(added.size(), bullet(added)));
        }

        if (!fixed.isEmpty()) {
            fail("""
                    ✅ 有 %d 条基线中的跨域依赖已被修复，请从基线文件里删除这些行（基线只减不增）：
                    %s
                    文件：%s"""
                    .formatted(fixed.size(), bullet(fixed), BASELINE));
        }

        assertTrue(currentLines.isEmpty() || !baselineLines.isEmpty(),
                "基线为空但代码仍有跨域依赖，说明基线被手工清空——请用 -Darchunit.baseline.write=true 重建");
    }

    // ==================================================================
    // P6-6 / D9：冻结表名规则（"删表之后不可能再有人读它们"）
    // ==================================================================

    /**
     * <b>生产代码不得再命名冻结的 {@code pms_*} 旧表（决策 D9）。</b>
     *
     * <h2>为什么这条规则必须存在（而且必须在删表之前就绿）</h2>
     * P6-6 删掉了单体侧最后一处商品表访问（mapper/实体/装配类），架构师随后会
     * {@code DROP TABLE mall.pms_*}。删表是<b>不可逆</b>的：只要还有一行代码按旧表名发 SQL，
     * 线上就会在运行到那条语句时炸。而"所有 mapper 都删了"这种结论靠一次性 grep 是守不住的——
     * 下一个人加一个 {@code @Select("SELECT ... FROM pms_spu")} 时，没有任何东西会拦他。
     * 所以把它变成棘轮式的规则：基线就是<b>0 违规</b>（不留白名单行，见任务书 D9）。
     *
     * <h2>判据：字符串字面量，不是"提到"</h2>
     * 规则只看<b>字符串字面量</b>（含文本块），因为只有字面量才会变成 SQL/表名发给数据库：
     * <ul>
     *   <li><b>注释/javadoc 里的提及不算</b>：本类正是靠这条才没有假阳性——{@code "本地直读写 {@code pms_*}"}
     *       这种历史注解在源码里成片存在（{@code EnableStatus}/{@code StockChangeType}/{@code OrderServiceImpl}…），
     *       它们不构成数据访问。所以这里做的是<b>词法级扫描</b>（跳过行注释、块注释、字符字面量），
     *       不是对文件做正则匹配——第一版用 {@code Select-String} 的口径就是这么误报 102 处的。</li>
     *   <li><b>散文/日志文案里的提及不判失败，但会被列出</b>：判据是"这个字面量是纯 ASCII 吗"。
     *       SQL 与表标识符必然是 ASCII；含非 ASCII（中文文案）的字面量只可能是给人看的消息，
     *       执行不出 SQL。唯一一例是 {@code OrderServiceImpl} 的回补失败日志（"查 pms_sku_stock_log …"），
     *       它是给 P8 对账的人看的话，删表后文案会指向一张不存在的表——所以本规则把它<b>打印出来</b>
     *       而不是配置一份文件白名单（白名单会让"以后有个新 mapper 写进同一个文件"被静默放过）。</li>
     * </ul>
     *
     * <h2>不查什么</h2>
     * <ul>
     *   <li>{@code .sql} 迁移/删表脚本：{@code db/03-drop-from-mall.sql} 这类文件<b>必须</b>写出表名才能干活，
     *       按任务书口径明确排除；</li>
     *   <li>{@code src/test}：测试替身 {@code ProductTestDoubleConfig} 故意用全限定名
     *       {@code mall_product.pms_sku} 直读<b>新库</b>（那才是属主），不属于"命名旧库表"。</li>
     * </ul>
     */
    @Test
    @DisplayName("D9 生产代码不得命名冻结的 pms_* 旧表（字符串字面量；注释与散文文案只列出不判失败）")
    void frozenPmsTablesMustNotBeNamedInProductionCode() throws IOException {
        List<Hit> codeHits = new ArrayList<>();
        List<Hit> proseHits = new ArrayList<>();
        int commentMentions = 0;
        List<Path> files;
        try (var walk = Files.walk(SOURCE_ROOT)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        for (Path file : files) {
            Scan scan = scanJava(Files.readString(file, StandardCharsets.UTF_8));
            commentMentions += scan.commentMentions();
            for (Literal literal : scan.literals()) {
                String table = frozenTableIn(literal.text());
                if (table == null) {
                    continue;
                }
                Hit hit = new Hit(file, literal.line(), table, literal.text());
                if (isProse(literal.text())) {
                    proseHits.add(hit);
                } else {
                    codeHits.add(hit);
                }
            }
        }

        printFrozenTableReport(codeHits, proseHits, commentMentions, files.size());

        assertTrue(codeHits.isEmpty(), """
                ❌ 生产代码里有 %d 处字符串字面量仍在命名已冻结的 pms_* 旧表（P6-6 / D9）：
                %s

                这些表（%s）的属主是 mall-product（schema mall_product），旧库 mall 里的副本即将被 DROP。
                请把访问改成走域契约（ProductQueryService / StockCommandService / ProductAdminClient）
                或删除这段本应随 P6-6 一起消失的代码——**不要**往本规则里加白名单。"""
                .formatted(codeHits.size(), bullet(codeHits.stream().map(Hit::describe).collect(Collectors.toCollection(TreeSet::new))),
                        String.join(", ", FROZEN_PMS_TABLES)));
    }

    // ==================================================================
    // P8-2a：认证域表名闸门（"单体不再依赖管理员账号表"）
    // ==================================================================

    /**
     * 认证域的管理员账号表（P7 起随认证域搬进 {@code mall-admin}，库 {@code mall_admin}）。
     *
     * <p>表名写在这里而不是散在断言里：本规则要守的就是"这个字符串在生产代码里彻底消失"。
     */
    private static final String FROZEN_ADMIN_TABLE = "sys_user";

    /** 被要求消失的实体/mapper（"连 mapper/DAO 一起删"这句要求变成可执行的） */
    private static final List<String> FROZEN_ADMIN_CLASSES = List.of(
            "com.mall.demo.admin.domain.AdminUser",
            "com.mall.demo.admin.mapper.AdminUserMapper");

    /**
     * <b>P8-2a：生产代码不得再引用认证域的管理员账号表。</b>
     *
     * <h2>为什么这条规则必须存在</h2>
     * P8-2 要把单体从 {@code mall} 库摘出来（连的是 {@code mall_trade}），而那里**没有**这张表。
     * 因此"单体不再读管理员账号表"不是一句注释，而是一条**删表前置条件**：
     * 只要还有一行代码按它发 SQL（mapper 上的 {@code @TableName}、手写 {@code @Select}），
     * 线上就会在运行到那条语句时炸 —— 与 P6-6 / D9 的 {@code pms_*} 是同一条教训。
     * 靠一次性 grep 守不住：下一个人补一个"顺手查一下管理员"的方法时，没有任何东西会拦他。
     *
     * <h2>判据（两条，都判失败）</h2>
     * <ol>
     *   <li><b>任何一行文本</b>（{@code src/main/java} + {@code src/main/resources}）里出现该表名 ——
     *       这就是验收里那条 {@code grep} 的可执行版本，**注释/javadoc 也算**：
     *       D9 对 {@code pms_*} 只把注释里的提及打印出来（那些历史注解成片存在），
     *       而本规则更严，因为这张表已经<b>整表</b>从本服务消失，"我们还在读它"式的注释只会误导读者；</li>
     *   <li>词法级的<b>字符串字面量</b>里出现该表名（会变成 SQL/表标识符的东西）——
     *       与 D9 同一套扫描器（跳过注释/字符字面量），报告口径也一致。</li>
     * </ol>
     * 另外断言两个类<b>真的不存在</b>了（{@code AdminUser} 实体与 {@code AdminUserMapper}）：
     * "把 mapper/DAO 一起删掉"这句话如果只靠人眼复核，很容易留下一个再也没人用的 mapper
     * （它不会报错，只会让人以为这张表还能读）。
     *
     * <h2>不查什么</h2>
     * {@code src/test}：测试基类里保留了一句"这里原来打的是单体自己的后台登录"的历史说明，
     * 那是"这个接口已经不在了"的证据，不是数据访问。
     */
    @Test
    @DisplayName("P8-2a 生产代码不得再引用认证域的管理员账号表（含注释），且实体/mapper 必须已删除")
    void adminAccountTableMustNotBeReferencedInProductionCode() throws IOException {
        List<String> lineHits = new ArrayList<>();
        List<Hit> literalHits = new ArrayList<>();
        int scanned = 0;
        for (Path root : List.of(SOURCE_ROOT, Path.of("src/main/resources"))) {
            if (!Files.exists(root)) {
                continue;
            }
            List<Path> files;
            try (var walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                scanned++;
                String text = Files.readString(file, StandardCharsets.UTF_8);
                int line = 1;
                for (String raw : text.split("\n", -1)) {
                    if (raw.contains(FROZEN_ADMIN_TABLE)) {
                        lineHits.add("%s:%d  %s".formatted(file.toString().replace('\\', '/'), line, raw.strip()));
                    }
                    line++;
                }
                if (file.toString().endsWith(".java")) {
                    for (Literal literal : scanJava(text).literals()) {
                        if (literal.text().contains(FROZEN_ADMIN_TABLE)) {
                            literalHits.add(new Hit(file, literal.line(), FROZEN_ADMIN_TABLE, literal.text()));
                        }
                    }
                }
            }
        }

        List<String> existingClasses = new ArrayList<>();
        var classes = new ClassFileImporter()
                .withImportOption(location -> !location.contains("test-classes"))
                .importPackages(ROOT);
        for (String fqn : FROZEN_ADMIN_CLASSES) {
            if (classes.stream().anyMatch(c -> c.getName().equals(fqn))) {
                existingClasses.add(fqn);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n================ P8-2a 认证域表名闸门（管理员账号表不得再出现在生产代码里） ================\n");
        sb.append("扫描范围：%s + src/main/resources（%d 个文件）%n".formatted(SOURCE_ROOT, scanned));
        sb.append("表名：%s（P7 起属 mall-admin / mall_admin）%n".formatted(FROZEN_ADMIN_TABLE));
        sb.append("命中（任何一行文本，含注释）：%d 处%n".formatted(lineHits.size()));
        lineHits.forEach(h -> sb.append("      ❌ ").append(h).append('\n'));
        sb.append("命中（字符串字面量）：%d 处%n".formatted(literalHits.size()));
        literalHits.forEach(h -> sb.append("      ❌ ").append(h.describe()).append('\n'));
        sb.append("仍然存在的实体/mapper：%s%n".formatted(existingClasses.isEmpty() ? "（无）" : existingClasses));
        sb.append("============================================================================================\n");
        System.out.print(sb);

        assertTrue(lineHits.isEmpty() && literalHits.isEmpty(), """
                ❌ 生产代码里仍有 %d 处引用认证域的管理员账号表 %s（其中字符串字面量 %d 处）：
                %s

                P8-2a 之后本服务的后台鉴权**只认网关注入的身份头**（X-Gateway-Auth / X-Admin-Id / X-Admin-Ver），
                不再验签、不再查账号表。请把这段访问改成信任网关身份（AdminIdentityResolver），
                **不要**往本规则里加白名单——这张表马上就要随 P8-5 从 mall 库删掉了。"""
                .formatted(lineHits.size() + literalHits.size(), FROZEN_ADMIN_TABLE, literalHits.size(),
                        bullet(new TreeSet<>(lineHits))));

        assertTrue(existingClasses.isEmpty(), """
                ❌ 认证域的实体/mapper 仍留在生产代码里：%s
                （AdminUser/AdminUserMapper 随"单体不再读账号表"一起删除；留着它们只会让人以为这张表还能读）"""
                .formatted(existingClasses));
    }

    // ==================================================================
    // 采集一：字节码层（运行期真实耦合）
    // ==================================================================

    /** 导入主代码字节码（排除测试类），返回全部跨模块直接依赖（已按"类对"去重） */
    private static List<Dep> collectBytecodeDependencies() {
        // 显式排除测试产物：本测试自身与 34 个 MySqlTestBase 用例都在 test-classes 下，
        // 若不排除，它们会被当成"模块内的类"参与判定，产生噪声。
        ImportOption mainCodeOnly = location -> !location.contains("test-classes");

        var classes = new ClassFileImporter()
                .withImportOption(mainCodeOnly)
                .importPackages(ROOT);

        Set<Dep> unique = new TreeSet<>(Comparator.comparing(Dep::key));
        for (JavaClass origin : classes) {
            String originModule = moduleOf(origin.getName());
            if (originModule == null) {
                continue;   // 启动类等不属于任何模块的类，跳过
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String targetName = normalize(dependency.getTargetClass().getName());
                String targetModule = moduleOf(targetName);
                if (targetModule == null || targetModule.equals(originModule)) {
                    continue;   // 域内依赖合法，不在本测试的管辖范围
                }
                unique.add(new Dep(originModule, normalize(origin.getName()), targetModule, targetName,
                        layerOf(targetName)));
            }
        }
        return new ArrayList<>(unique);
    }

    // ==================================================================
    // 采集二：源码 import 层（编译期耦合，含被 javac 内联的常量）
    // ==================================================================

    /** 扫描 {@code src/main/java} 的 import 语句，返回全部跨模块 import */
    private static List<Dep> collectSourceImports() throws IOException {
        Set<Dep> unique = new TreeSet<>(Comparator.comparing(Dep::key));
        List<Path> files;
        try (var walk = Files.walk(SOURCE_ROOT)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path file : files) {
            String originClass = fqnOf(file);
            String originModule = moduleOf(originClass);
            if (originModule == null) {
                continue;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = IMPORT_PATTERN.matcher(text);
            while (matcher.find()) {
                String target = matcher.group(1);
                String targetModule = moduleOf(target);
                if (targetModule == null || targetModule.equals(originModule)) {
                    continue;
                }
                unique.add(new Dep(originModule, originClass, targetModule, target, layerOf(target)));
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * 把内部类归并到外层类（{@code JwtUtil$Claims} → {@code JwtUtil}）。
     *
     * <p>理由：内部类在字节码层是独立的 {@code JavaClass}，但它与代码里的 import 语句是同一件事
     * （import 的是外层类），不归并会让同一处耦合被记两条，基线虚高、消减进度失真。
     */
    private static String normalize(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    /** {@code src/main/java/com/mall/demo/oms/service/impl/OrderServiceImpl.java}
     *  → {@code com.mall.demo.oms.service.impl.OrderServiceImpl} */
    private static String fqnOf(Path file) {
        String relative = SOURCE_ROOT.relativize(file).toString();
        return relative.replace(".java", "").replace('\\', '.').replace('/', '.');
    }

    // ==================================================================
    // 模块/层解析
    // ==================================================================

    /** {@code com.mall.demo.oms.service.impl.OrderServiceImpl} → {@code oms}；不属于任何模块则返回 null */
    private static String moduleOf(String className) {
        if (!className.startsWith(ROOT + ".")) {
            return null;
        }
        String rest = className.substring(ROOT.length() + 1);
        int dot = rest.indexOf('.');
        String first = dot < 0 ? rest : rest.substring(0, dot);
        return MODULES.contains(first) ? first : null;
    }

    /**
     * {@code com.mall.demo.pms.mapper.SkuMapper} → {@code mapper}；
     * 类直接位于模块包下（如 {@code com.mall.demo.common.ApiResponse}）→ 空串。
     */
    private static String layerOf(String className) {
        if (!className.startsWith(ROOT + ".")) {
            return "";
        }
        String[] segments = className.substring(ROOT.length() + 1).split("\\.");
        if (segments.length < 3) {
            return "";
        }
        return LAYERS.contains(segments[1]) ? segments[1] : "";
    }

    // ==================================================================
    // 基线读写
    // ==================================================================

    private static Set<String> keys(List<Dep> deps, Rule rule) {
        return deps.stream()
                .filter(rule.predicate)
                .map(Dep::key)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private enum Strategy { BC_ONLY, IMPORT_ONLY, BOTH }

    private static int count(Set<String> bc, Set<String> im, Strategy strategy) {
        return switch (strategy) {
            case BC_ONLY -> (int) bc.stream().filter(k -> !im.contains(k)).count();
            case IMPORT_ONLY -> (int) im.stream().filter(k -> !bc.contains(k)).count();
            case BOTH -> (int) bc.stream().filter(im::contains).count();
        };
    }

    private static Set<String> flatten(Map<String, Set<String>> byRule) {
        Set<String> lines = new TreeSet<>();
        byRule.forEach((ruleId, keys) -> keys.forEach(key -> lines.add(ruleId + "|" + key)));
        return lines;
    }

    private static void writeBaseline(Set<String> lines) throws IOException {
        Files.createDirectories(BASELINE.getParent());
        StringBuilder sb = new StringBuilder(HEADER);
        lines.forEach(line -> sb.append(line).append('\n'));
        Files.writeString(BASELINE, sb.toString(), StandardCharsets.UTF_8);
    }

    private static Set<String> readBaseline() throws IOException {
        Set<String> lines = new TreeSet<>();
        for (String raw : Files.readAllLines(BASELINE, StandardCharsets.UTF_8)) {
            // 容忍 BOM：Windows 上的编辑器/脚本很可能给文件加上 U+FEFF，
            // 若不去掉，第一行注释会被当成"数据行"，从而报出一条假的"基线待收缩"。
            String line = raw.strip().replace("\uFEFF", "").strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            lines.add(line);
        }
        return lines;
    }

    // ==================================================================
    // 报告输出（直接印在 mvn test 输出里，便于随时查看消减进度）
    // ==================================================================

    private static void printReport(Map<String, Set<String>> byRule,
                                    Map<String, int[]> layerStats,
                                    List<Dep> allDeps,
                                    List<Dep> bytecodeDeps,
                                    List<Dep> importDeps) {
        int total = byRule.values().stream().mapToInt(Set::size).sum();
        Set<String> allowedKeys = allDeps.stream()
                .filter(dep -> Arrays.stream(Rule.values()).anyMatch(r -> r.predicate.test(dep)))
                .map(Dep::key)
                .collect(Collectors.toCollection(TreeSet::new));
        List<Dep> violations = allDeps.stream().filter(d -> allowedKeys.contains(d.key())).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("\n================ P0 跨域依赖清单（棘轮基线） ================\n");
        for (Rule rule : Rule.values()) {
            Set<String> keys = byRule.getOrDefault(rule.name(), Set.of());
            int[] stats = layerStats.getOrDefault(rule.name(), new int[3]);
            sb.append("%-3s %-50s %4d 条%n".formatted(rule.name(), rule.title(), keys.size()));
            if (!keys.isEmpty()) {
                sb.append("      来源：仅字节码 %d · 仅import %d · 两者都有 %d%n"
                        .formatted(stats[0], stats[1], stats[2]));
            }
            keys.stream().limit(5).forEach(k -> sb.append("      · ").append(k).append('\n'));
            if (keys.size() > 5) {
                sb.append("      …… 其余 ").append(keys.size() - 5).append(" 条见基线文件\n");
            }
        }
        sb.append("------------------------------------------------------------\n");
        sb.append("违规合计：%d 条（P0 目标：0）%n".formatted(total));
        sb.append("采集规模：字节码层跨模块依赖 %d 条 · import 层跨模块依赖 %d 条 · 合并去重后 %d 条%n"
                .formatted(bytecodeDeps.size(), importDeps.size(), allDeps.size()));
        long serviceDeps = allDeps.stream().filter(Dep::isServiceInterfaceDependency).count();
        sb.append("其中\"域服务接口\"形态 %d 条（P0 的目标形态，不拦截，P8 起改 HTTP 调用）%n".formatted(serviceDeps));

        Map<String, Long> byPair = violations.stream()
                .collect(Collectors.groupingBy(Dep::modulePair, TreeMap::new, Collectors.counting()));
        sb.append("违规按模块对：").append(byPair.isEmpty() ? "（无）" : byPair.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("  "))).append('\n');

        Map<String, Long> byLayer = violations.stream()
                .collect(Collectors.groupingBy(d -> d.targetLayer().isEmpty() ? "(模块根)" : d.targetLayer(),
                        TreeMap::new, Collectors.counting()));
        sb.append("违规按目标层：").append(byLayer.isEmpty() ? "（无）" : byLayer.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("  "))).append('\n');
        sb.append("============================================================\n");
        System.out.print(sb);
    }

    private static String bullet(Set<String> lines) {
        int limit = 25;
        String head = lines.stream().limit(limit).map(l -> "  " + l).collect(Collectors.joining("\n"));
        return lines.size() <= limit
                ? head
                : head + "\n  ...（其余 %d 条见基线文件）".formatted(lines.size() - limit);
    }

    // ==================================================================
    // P6-6 / D9 的采集与报告
    // ==================================================================

    /** 一个字符串字面量（含文本块内容）：{@code line} 是字面量<b>开始</b>的那一行 */
    private record Literal(int line, String text) {
    }

    /** 一个"命中了冻结表名"的字符串字面量 */
    private record Hit(Path file, int line, String table, String literal) {

        String describe() {
            String text = literal.strip();
            if (text.length() > 90) {
                text = text.substring(0, 90) + "…";
            }
            return "%s:%d  [%s]  \"%s\"".formatted(file.toString().replace('\\', '/'), line, table, text);
        }
    }

    /** 一次词法扫描的结果：真正的字符串字面量 + 注释里提到冻结表名的处数（仅用于报告口径） */
    private record Scan(List<Literal> literals, int commentMentions) {
    }

    /**
     * Java 源码的<b>词法级</b>扫描：按状态机走一遍字符流，只把"真正的字符串字面量"收集出来。
     *
     * <p>为什么不用正则（{@code "([^"]*)"}）：注释里的引号（文档里写 {@code @TableName("mall_product.pms_sku")}）、
     * 字符串里的 {@code //} 都会让正则错位；而这条规则一旦误报就会被人用白名单压掉，等于规则失效。
     * 这里显式处理：行注释、块注释、字符字面量、转义、文本块（{@code """…"""}）。
     */
    private static Scan scanJava(String text) {
        List<Literal> literals = new ArrayList<>();
        int commentMentions = 0;
        int line = 1;
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                int end = text.indexOf('\n', i);
                if (end < 0) {
                    end = n;
                }
                if (frozenTableIn(text.substring(i, end)) != null) {
                    commentMentions++;
                }
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int close = text.indexOf("*/", i + 2);
                int end = close < 0 ? n : close + 2;
                String body = text.substring(i, end);
                if (frozenTableIn(body) != null) {
                    commentMentions++;
                }
                line += countNewlines(body);
                i = end;
                continue;
            }
            if (c == '"') {
                int startLine = line;
                if (text.startsWith("\"\"\"", i)) {
                    // 文本块：内容也是"会变成 SQL 的东西"，所以照样算字面量
                    int j = i + 3;
                    while (j < n && !(text.startsWith("\"\"\"", j) && text.charAt(j - 1) != '\\')) {
                        j++;
                    }
                    int end = Math.min(j, n);
                    literals.add(new Literal(startLine, text.substring(i + 3, end)));
                    int consumedEnd = Math.min(end + 3, n);
                    line += countNewlines(text.substring(i, consumedEnd));
                    i = consumedEnd;
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < n) {
                    char d = text.charAt(j);
                    if (d == '\\' && j + 1 < n) {
                        sb.append(d).append(text.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    if (d == '"') {
                        break;
                    }
                    if (d == '\n') {
                        line++;   // 非法 Java（未转义的换行），容错处理
                    }
                    sb.append(d);
                    j++;
                }
                literals.add(new Literal(startLine, sb.toString()));
                i = j + 1;
                continue;
            }
            if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    char d = text.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '\'') {
                        break;
                    }
                    j++;
                }
                i = j + 1;
                continue;
            }
            i++;
        }
        return new Scan(literals, commentMentions);
    }

    /** 字面量里命中的冻结表名（最长匹配优先，因为 {@code pms_spu} 是 {@code pms_spu_detail} 的前缀） */
    private static String frozenTableIn(String text) {
        for (String table : FROZEN_PMS_TABLES_LONGEST_FIRST) {
            if (text.contains(table)) {
                return table;
            }
        }
        return null;
    }

    /**
     * 是"散文/日志文案"而非 SQL/表标识符：判据是<b>含非 ASCII 字符</b>。
     * SQL 与表名只可能是 ASCII；出现中文说明这条字面量是给人看的话，执行不出 SQL。
     */
    private static boolean isProse(String literal) {
        return literal.chars().anyMatch(ch -> ch > 0x7F);
    }

    private static int countNewlines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static void printFrozenTableReport(List<Hit> codeHits,
                                               List<Hit> proseHits,
                                               int commentMentions,
                                               int scannedFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n================ P6-6 / D9 冻结表名闸门（pms_* 不得再出现在生产代码的字面量里） ================\n");
        sb.append("扫描范围：%s（%d 个 .java；.sql 迁移脚本与 src/test 按口径排除）%n"
                .formatted(SOURCE_ROOT, scannedFiles));
        sb.append("冻结表（%d 张）：%s%n".formatted(FROZEN_PMS_TABLES.size(), String.join(", ", FROZEN_PMS_TABLES)));
        sb.append("-".repeat(60)).append('\n');
        sb.append("违规（字符串字面量命名了旧表）：%d 处%n".formatted(codeHits.size()));
        codeHits.forEach(h -> sb.append("      ❌ ").append(h.describe()).append('\n'));
        sb.append("提示（散文/日志文案里提到旧表，不判失败）：%d 处%n".formatted(proseHits.size()));
        proseHits.forEach(h -> sb.append("      ⚠️ ").append(h.describe()).append('\n'));
        sb.append("说明（注释/javadoc 里的历史提及，不判失败）：%d 处%n".formatted(commentMentions));
        sb.append("============================================================\n");
        System.out.print(sb);
    }
}
