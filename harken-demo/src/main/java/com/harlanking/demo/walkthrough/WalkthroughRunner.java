package com.harlanking.demo.walkthrough;

import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.api.ReadOnlyRequest;
import com.harlanking.harken.core.api.ReadWriteRequest;
import com.harlanking.harken.core.api.ScriptRequest;
import com.harlanking.harken.core.api.ScriptResult;
import com.harlanking.harken.core.metadata.ClassIndex;
import com.harlanking.harken.core.metadata.MethodCatalog;
import com.harlanking.harken.core.metadata.MethodMetadata;
import com.harlanking.harken.core.spi.AuditRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 演示脚本：十五步走完「只读 → 试跑 → 提交 → 被拒 → 反查」。
 *
 * <h2>为什么不是「跑一行 1+1 看看」</h2>
 * {@code return 1+1} 只能证明引擎能算加法，证明不了这个工具存在的理由。
 * 真正需要被验证的是四件事，每一步都对其中一件下断言：
 * <ol>
 *   <li><b>写操作不提交，事务外真的是旧值</b>（第 2、3 步）—— 这是全部价值的地基</li>
 *   <li><b>提交之后真的落库</b>（第 4、5 步）—— 否则前一条只是"什么都没做"</li>
 *   <li><b>危险动作在跑之前就被拦住</b>（第 6~9、11~14 步）—— 拦住比事后审计便宜</li>
 *   <li><b>被拦住的那次留下了记录，并且查得到</b>（第 10、15 步）—— 否则「有审计」是错觉</li>
 * </ol>
 *
 * <p>每一步都带一个 {@link Predicate} 自校验，输出末尾会打印「N/15 符合预期」。
 * 演示脚本自己会失败 —— 这样它就不是一段好看的日志，而是一个能回归的验收用例。
 *
 * @author harlan
 * @since 1.0.0
 */
@Component
@Order(100)
public class WalkthroughRunner implements ApplicationRunner {

    private static final String RULE = "========================================================================";
    private static final String THIN = "------------------------------------------------------------------------";

    private final HarkenService harken;
    private final ClassIndex classIndex;
    private final ApplicationContext context;
    private final JdbcTemplate jdbc;
    private final boolean exitAfter;

    /**
     * @param harken     starter 自动配置出来的编排器，业务代码只管注入
     * @param classIndex 类名索引，同样来自自动配置
     * @param context    用于优雅退出
     * @param jdbc       直连数据库，用来复核「事务外到底是什么值」与审计表内容
     * @param exitAfter  演示跑完是否退出进程
     */
    public WalkthroughRunner(HarkenService harken,
                             ClassIndex classIndex,
                             ApplicationContext context,
                             JdbcTemplate jdbc,
                             @Value("${demo.exit-after:false}") boolean exitAfter) {
        this.harken = harken;
        this.classIndex = classIndex;
        this.context = context;
        this.jdbc = jdbc;
        this.exitAfter = exitAfter;
    }

    /**
     * 跑完整个演示。
     *
     * @param args 启动参数，未使用
     */
    @Override
    public void run(ApplicationArguments args) {
        List<Step> steps = buildSteps();
        int passed = 0;

        StringBuilder report = new StringBuilder(4096);
        report.append('\n').append(RULE).append('\n');
        report.append(" Harken demo - ").append(steps.size())
                .append(" steps: read-only -> dry run -> commit -> rejected\n");
        report.append(" 事务: ").append(harken.transactionDescription())
                .append("  |  守卫: ").append(harken.writeGuardDescription()).append('\n');
        report.append(" 审计: ").append(harken.auditDescription()).append('\n');
        report.append(RULE).append('\n');

        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            ScriptResult result = execute(step.request());
            boolean asExpected = step.check().test(result);
            if (asExpected) {
                passed++;
            }
            appendStep(report, i + 1, step, result, asExpected);
        }

        report.append(THIN).append('\n');
        report.append(passed).append('/').append(steps.size()).append(" 步符合预期");
        report.append(passed == steps.size() ? "  ✓" : "  ✗ 上面有不符合预期的步骤，按预期值那行对比").append('\n');
        appendMetadata(report);
        report.append(RULE).append('\n');

        System.out.print(report);

        if (exitAfter) {
            final int exitCode = passed == steps.size() ? 0 : 1;
            System.out.println("demo.exit-after=true，退出。");
            System.exit(SpringApplication.exit(context, () -> exitCode));
        } else {
            System.out.println("应用仍在运行。M2 的 HTTP 控制台会挂在这个进程里。Ctrl+C 退出。");
        }
    }

    // ------------------------------------------------------------------
    // 十五个步骤
    // ------------------------------------------------------------------

    private List<Step> buildSteps() {
        List<Step> steps = new ArrayList<>(15);

        steps.add(new Step(
                "AI 通道读一个集合（ReadOnlyRequest）",
                "只读请求没有 commit 字段，AI 想提交在类型上就写不出来",
                new ReadOnlyRequest("orderService.topAmount(3)"),
                "成功，返回金额前三的订单；事务必然回滚",
                r -> r.ok() && r.rolledBack() && r.result() != null && r.result().contains("SO-1002")));

        steps.add(new Step(
                "人类试跑一次写操作（commit=false）",
                "脚本里改完立刻自己查一次，事务内能看见变化 —— 说明 UPDATE 真的执行了",
                new ReadWriteRequest("""
                        def n = orderService.markPaid('SO-1002')
                        "affected=${n}, in-tx status=" + orderService.statusOf('SO-1002')
                        """, false),
                "成功；affected=1，事务内 status=PAID；rolledBack=true",
                r -> r.ok() && r.rolledBack()
                        && r.result() != null && r.result().contains("affected=1")
                        && r.result().contains("in-tx status=PAID")));

        steps.add(new Step(
                "事务外复核：改动必须消失",
                "这一步是整个工具的地基。看不到这一行，前面那次 UPDATE 就等于真改了库",
                new ReadWriteRequest("orderService.statusOf('SO-1002')", false),
                "status 仍然是 NEW",
                r -> r.ok() && "NEW".equals(r.result())));

        steps.add(new Step(
                "人类提交（commit=true）",
                "同一个方法、同一行 SQL，唯一区别是这次要提交",
                new ReadWriteRequest("""
                        def n = orderService.markPaid('SO-1002')
                        "affected=${n}"
                        """, true),
                "成功；rolledBack=false",
                r -> r.ok() && !r.rolledBack() && r.result() != null && r.result().contains("affected=1")));

        steps.add(new Step(
                "事务外复核：改动必须留下",
                "提交过就不该被回滚，否则第 4 步的 ok=true 是假的",
                new ReadWriteRequest("orderService.statusOf('SO-1002')", false),
                "status 变成 PAID",
                r -> r.ok() && "PAID".equals(r.result())));

        // --------------------------------------------------------------
        // 数据保护三件套：行数闸 / 表黑名单 / 审计落库
        // --------------------------------------------------------------

        steps.add(new Step(
                "影响行数闸：一次改全表被拦下",
                "少写一个 where 是控制台里最常见的事故。上限配的是 2 行，这条 SQL 会改 5 行",
                new ReadWriteRequest("orderService.markAllPaid()", false),
                "失败；errorType=HARKEN_ROW_LIMIT，事务回滚",
                r -> !r.ok() && "HARKEN_ROW_LIMIT".equals(r.errorType()) && r.rolledBack()));

        steps.add(new Step(
                "事务外复核：被拦下的 5 行一条都没动",
                "行数闸的价值不在报错，而在「报错时数据是干净的」",
                new ReadWriteRequest("orderService.statusOf('SO-1003')", false),
                "status 仍是 NEW",
                r -> r.ok() && "NEW".equals(r.result())));

        steps.add(new Step(
                "关键表黑名单：写 supply_order 被拒，读它照常",
                "黑名单只拦写。连读都拦的话，排查问题的第一屏就卡住了",
                new ReadWriteRequest("orderService.closeAllSupplyOrders()", false),
                "失败；errorType=HARKEN_TABLE_FORBIDDEN；表里仍是 2 条 OPEN",
                r -> !r.ok() && "HARKEN_TABLE_FORBIDDEN".equals(r.errorType())
                        && openSupplyOrders() == 2));

        steps.add(new Step(
                "DDL 拦截：脚本不能改表结构",
                "harken.guard.deny-ddl=true。紧急加字段请走发布流程，别在控制台里顺手 alter",
                new ReadWriteRequest("orderService.createArchiveTable()", false),
                "失败；errorType=HARKEN_DDL_FORBIDDEN",
                r -> !r.ok() && "HARKEN_DDL_FORBIDDEN".equals(r.errorType())));

        steps.add(new Step(
                "审计落库：上面三次被拒，表里都有记录",
                "审计写在业务事务之外，脚本回滚它也在；affected_rows 记下了「本来会改 5 行」",
                new ReadOnlyRequest("orderService.count()"),
                "harken_audit 里有三条被拒记录，其中行数闸那条 affected_rows=5",
                r -> r.ok()
                        && auditCount("HARKEN_ROW_LIMIT") >= 1
                        && auditCount("HARKEN_TABLE_FORBIDDEN") >= 1
                        && auditCount("HARKEN_DDL_FORBIDDEN") >= 1
                        && auditMaxAffected("HARKEN_ROW_LIMIT") == 5));

        steps.add(new Step(
                "语法错误：编译期就带行列号返回",
                "拼错一半就回车是常态，错误必须指到具体某一行",
                new ReadWriteRequest("""
                        def list = orderService.topAmount(2)
                        return list.
                        """, false),
                "失败；errorType=COMPILATION",
                r -> !r.ok() && "COMPILATION".equals(r.errorType())));

        steps.add(new Step(
                "运行期异常：方法不存在",
                "抄了别人的脚本但 Bean 里没这个方法，错误信息要能直接定位",
                new ReadWriteRequest("orderService.noSuchMethod()", false),
                "失败；errorType=MissingMethodException",
                r -> !r.ok() && r.errorType() != null && r.errorType().contains("MissingMethodException")));

        steps.add(new Step(
                "只读通道拦截副作用 API",
                "文件、进程、发消息都不在数据库事务里，回滚兜不住，只能在跑之前拦",
                new ReadOnlyRequest("Runtime.getRuntime().exec('id')"),
                "失败；errorType=HARKEN_FORBIDDEN_API",
                r -> !r.ok() && "HARKEN_FORBIDDEN_API".equals(r.errorType())));

        steps.add(new Step(
                "拦截编译期执行特性（对所有人）",
                "@Grab 会在编译阶段去下载 jar，跟恶意无关，是防自伤",
                new ReadWriteRequest("@Grab('org.example:whatever:1.0')\nreturn 1", true),
                "失败；errorType=HARKEN_COMPILE_TIME_EVAL",
                r -> !r.ok() && "HARKEN_COMPILE_TIME_EVAL".equals(r.errorType())));

        // --------------------------------------------------------------
        // 反查：不讲这个，用户只能去翻日志文件
        // --------------------------------------------------------------

        steps.add(new Step(
                "反查最近执行（内存环形缓冲）",
                "控制台的「最近执行」面板靠它。零建表、零权限，和 jdbc 审计并行写",
                new ReadOnlyRequest("orderService.count()"),
                "能一次拿到本次会话的所有记录，含上面被拒的那几次",
                r -> r.ok()
                        && recentAudits(50).size() >= 14
                        && recentAudits(50).stream()
                                .anyMatch(a -> "HARKEN_ROW_LIMIT".equals(a.errorType()))
                        && recentAudits(50).stream()
                                .anyMatch(a -> "harlan".equals(a.operator()))));

        return steps;
    }

    private ScriptResult execute(ScriptRequest request) {
        if (request instanceof ReadOnlyRequest readOnly) {
            return harken.execute(readOnly, "ai-agent", "127.0.0.1");
        }
        return harken.execute((ReadWriteRequest) request, "harlan", "127.0.0.1");
    }

    // ------------------------------------------------------------------
    // 直连数据库的复核。这些查询不走脚本通道，是为了证明「从外面看」到底是什么。
    // ------------------------------------------------------------------

    /**
     * @return 状态仍是 OPEN 的供应单数量
     */
    private long openSupplyOrders() {
        try {
            Long n = jdbc.queryForObject(
                    "select count(*) from supply_order where status = 'OPEN'", Long.class);
            return n == null ? -1L : n;
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    /**
     * @param errorType 错误类型
     * @return 审计表里该错误类型的记录数，查不到返回 {@code -1}
     */
    private long auditCount(String errorType) {
        try {
            Long n = jdbc.queryForObject(
                    "select count(*) from harken_audit where error_type = ?", Long.class, errorType);
            return n == null ? -1L : n;
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    /**
     * @param errorType 错误类型
     * @return 该错误类型记录里的最大影响行数，查不到返回 {@code -1}
     */
    private long auditMaxAffected(String errorType) {
        try {
            Long n = jdbc.queryForObject(
                    "select max(affected_rows) from harken_audit where error_type = ?", Long.class, errorType);
            return n == null ? -1L : n;
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    /**
     * 从审计后端反查最近的记录。没挂可查询的存储时返回空列表 ——
     * 这正是「配了内存审计才有得查」这件事的可见证据。
     *
     * @param limit 最多几条
     * @return 记录列表，永不为 {@code null}
     */
    private List<AuditRecord> recentAudits(int limit) {
        try {
            return harken.recentAudits(limit);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // 输出
    // ------------------------------------------------------------------

    private void appendStep(StringBuilder sb, int index, Step step, ScriptResult r, boolean asExpected) {
        sb.append('\n').append('[').append(index).append("] ").append(step.title()).append('\n');
        sb.append("    说明  ").append(step.note()).append('\n');
        sb.append("    脚本  ").append(oneLine(step.request().script())).append('\n');
        sb.append("    预期  ").append(step.expectation()).append('\n');
        sb.append("    实际  ok=").append(r.ok())
                .append("  rolledBack=").append(r.rolledBack())
                .append("  ").append(r.durationMs()).append("ms")
                .append("  channel=").append(r.channel()).append('\n');
        if (r.result() != null) {
            sb.append("    结果  ").append(oneLine(r.result())).append('\n');
        }
        if (r.output() != null && !r.output().isBlank()) {
            sb.append("    输出  ").append(oneLine(r.output())).append('\n');
        }
        if (r.error() != null) {
            sb.append("    错误  ").append(r.errorType()).append("  ").append(oneLine(firstLine(r.error()))).append('\n');
        }
        if (r.truncated()) {
            sb.append("    截断  ").append(r.hint()).append('\n');
        }
        sb.append("    >> ").append(asExpected ? "符合预期" : "不符合预期").append('\n');
    }

    private void appendMetadata(StringBuilder sb) {
        try {
            MethodCatalog catalog = new MethodCatalog(harken.beanResolver());
            sb.append('\n').append("补全元数据（类名索引 + 按需方法表，扫的时候不实例化 Bean）").append('\n');
            sb.append("  业务类 ").append(classIndex.size())
                    .append(" 个，分布在 ").append(classIndex.byPackage().size()).append(" 个包；")
                    .append("Bean ").append(catalog.beanTypes().size()).append(" 个").append('\n');
            for (String beanName : catalog.beanTypes().keySet()) {
                String methods = catalog.instanceMethods(beanName).stream()
                        .map(MethodMetadata::name)
                        .distinct()
                        .collect(Collectors.joining(", "));
                sb.append("  ").append(beanName)
                        .append("  ->  ").append(methods).append('\n');
            }
        } catch (RuntimeException e) {
            sb.append("\n元数据读取失败（不影响执行）：").append(e).append('\n');
        }
    }

    private static String firstLine(String text) {
        int idx = text.indexOf('\n');
        return idx < 0 ? text : text.substring(0, idx);
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace("\r", "").replace("\n", " ⏎ ").trim();
        return flat.length() <= 220 ? flat : flat.substring(0, 220) + " ...";
    }

    /**
     * 一个演示步骤：脚本 + 预期 + 校验方式。
     *
     * @param title       标题
     * @param note        这一步在证明什么
     * @param request     请求对象（两种通道由类型决定）
     * @param expectation 人读的预期
     * @param check       机器校验
     */
    private record Step(String title, String note, ScriptRequest request,
                        String expectation, Predicate<ScriptResult> check) {
    }
}
