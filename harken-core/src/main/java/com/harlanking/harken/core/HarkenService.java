package com.harlanking.harken.core;

import com.harlanking.harken.core.api.ReadOnlyRequest;
import com.harlanking.harken.core.api.ReadWriteRequest;
import com.harlanking.harken.core.api.ScriptRequest;
import com.harlanking.harken.core.api.ScriptResult;
import com.harlanking.harken.core.engine.ExecutionContext;
import com.harlanking.harken.core.engine.ScriptEngine;
import com.harlanking.harken.core.engine.ScriptExecutor;
import com.harlanking.harken.core.impl.Slf4jAuditStore;
import com.harlanking.harken.core.redact.Redactor;
import com.harlanking.harken.core.spi.AuditRecord;
import com.harlanking.harken.core.spi.AuditStore;
import com.harlanking.harken.core.spi.BeanResolver;
import com.harlanking.harken.core.spi.BindingContributor;
import com.harlanking.harken.core.spi.ScriptValidator;
import com.harlanking.harken.core.spi.TransactionBridge;
import com.harlanking.harken.core.spi.WriteGuard;
import com.harlanking.harken.core.catalog.NoopScriptCatalog;
import com.harlanking.harken.core.catalog.ScriptCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 执行编排。整个工具的骨架就是这一个方法的流程：
 *
 * <pre>
 * 长度检查 → 校验 → 事务模式决策 → 注入变量 → [守卫作用域内] 事务内执行 → 截断 → 审计（事务外）
 * </pre>
 *
 * <h3>三个不可动摇的顺序</h3>
 * <ol>
 *   <li><b>审计必须在事务之外。</b> 写在事务里，脚本回滚时审计跟着回滚——而"被回滚的那次"
 *       恰恰是最需要审计的（有人试图改数据但被兜住了）。</li>
 *   <li><b>事务必须在执行脚本的那个线程里开。</b> Spring 的事务是 ThreadLocal 绑定的，
 *       在父线程开事务、子线程跑脚本，脚本里拿到的连接根本没有事务上下文。
 *       所以 {@code transactionBridge.inTransaction} 要包在 {@code executor.call} 的
 *       lambda 内部，而不是外面。</li>
 *   <li><b>截断在审计之前。</b> 审计存摘要，不存完整返回值。</li>
 * </ol>
 *
 * <h3>线程模型</h3>
 * <p>本类所有方法都是线程安全的：内部不持有可变状态，每次都新建 {@code ExecutionContext}
 * 与输出缓冲。可以注册成单例 Bean。
 *
 * <h3>典型用法（Spring 场景请用 starter，不要手写这段）</h3>
 * <pre>{@code
 * ScriptResult r = harken.execute(
 *         new ReadWriteRequest("return orderService.count()", false), "harlan", "10.0.1.5");
 * if (r.ok()) {
 *     System.out.println(r.result());   // 返回值
 *     System.out.println(r.rolledBack()); // true = 没落库
 * }
 * }</pre>
 *
 * @author harlan
 * @since 1.0.0
 */
public final class HarkenService {

    private static final Logger log = LoggerFactory.getLogger(HarkenService.class);

    private static final int MAX_ERROR_BYTES = 4_000;
    private static final int MAX_AUDIT_ERROR_BYTES = 2_000;

    private final ScriptEngine engine;
    private final ScriptExecutor executor = new ScriptExecutor();
    private final BeanResolver beanResolver;
    private final List<BindingContributor> contributors;
    private final List<ScriptValidator> validators;
    private final List<AuditStore> auditStores;
    private final TransactionBridge transactionBridge;
    private final WriteGuard writeGuard;
    private final HarkenOptions options;
    private final ScriptCatalog catalog;

    /**
     * 创建不带写入守卫的编排器。等价于传 {@link WriteGuard#NONE}。
     *
     * <p>保留这个重载是为了向后兼容：升级到有守卫的版本时，
     * 老调用方不用改任何一行代码，只是失去「影响行数」这项审计信息。
     *
     * @param engine            脚本引擎，不能为 {@code null}
     * @param beanResolver      Bean 解析器；{@code null} 表示没有可访问的 Bean
     * @param contributors      变量注入器，可为 {@code null}
     * @param validators        校验器，可为 {@code null}
     * @param auditStores       审计存储，为空时使用 {@link Slf4jAuditStore}
     * @param transactionBridge 事务桥；{@code null} 等于「无事务保护」
     * @param options           执行参数；{@code null} 使用默认值
     * @throws NullPointerException 当 {@code engine} 为 {@code null}
     */
    public HarkenService(ScriptEngine engine,
                         BeanResolver beanResolver,
                         List<BindingContributor> contributors,
                         List<ScriptValidator> validators,
                         List<AuditStore> auditStores,
                         TransactionBridge transactionBridge,
                         HarkenOptions options) {
        this(engine, beanResolver, contributors, validators, auditStores,
                transactionBridge, options, WriteGuard.NONE);
    }

    /**
     * 创建编排器。一般都由 {@link HarkenRuntime.Builder} 或自动配置调用，业务代码不需要直接 new。
     *
     * <p>对 {@code null} 的处理是刻意的：除了 {@code engine} 必须存在，
     * 其余参数缺失都会退化成一个安全的默认值（空 Bean 解析器、写日志审计、
     * 无事务桥、无守卫），保证「最少配置也能跑起来」。
     *
     * @param engine            脚本引擎，不能为 {@code null}
     * @param beanResolver      Bean 解析器；{@code null} 表示没有可访问的 Bean
     * @param contributors      变量注入器，按 {@link BindingContributor#getOrder()} 升序执行；可为 {@code null}
     * @param validators        校验器，按 order 升序执行，任一返回错误即拒绝；可为 {@code null}
     * @param auditStores       审计存储，可多个；为空时使用 {@link Slf4jAuditStore}
     * @param transactionBridge 事务桥；{@code null} 等于「无事务保护」，写操作会被拒绝
     * @param options           执行参数；{@code null} 使用 {@link HarkenOptions#defaults()}
     * @param writeGuard        写入守卫；{@code null} 等于不检查也不统计影响行数
     * @throws NullPointerException 当 {@code engine} 为 {@code null}
     */
    public HarkenService(ScriptEngine engine,
                         BeanResolver beanResolver,
                         List<BindingContributor> contributors,
                         List<ScriptValidator> validators,
                         List<AuditStore> auditStores,
                         TransactionBridge transactionBridge,
                         HarkenOptions options,
                         WriteGuard writeGuard) {
        this(engine, beanResolver, contributors, validators, auditStores,
                transactionBridge, options, writeGuard, NoopScriptCatalog.INSTANCE);
    }

    /**
     * 创建编排器（含预置脚本目录）。
     *
     * <p>{@code catalog} 是受限模式的数据来源：它持有 {@link ScriptCatalog}，
     * UI 在受限模式下用目录替代自由编辑器。没有配置目录时用 {@link NoopScriptCatalog}
     * （空列表），{@code catalog()} 永不返回 {@code null}。
     *
     * @param engine            脚本引擎，不能为 {@code null}
     * @param beanResolver      Bean 解析器；{@code null} 表示没有可访问的 Bean
     * @param contributors      变量注入器，按 {@link BindingContributor#getOrder()} 升序执行；可为 {@code null}
     * @param validators        校验器，按 order 升序执行，任一返回错误即拒绝；可为 {@code null}
     * @param auditStores       审计存储，可多个；为空时使用 {@link com.harlanking.harken.core.impl.Slf4jAuditStore}
     * @param transactionBridge 事务桥；{@code null} 等于「无事务保护」，写操作会被拒绝
     * @param options           执行参数；{@code null} 使用 {@link HarkenOptions#defaults()}
     * @param writeGuard        写入守卫；{@code null} 等于不检查也不统计影响行数
     * @param catalog           预置脚本目录；{@code null} 等于空目录
     * @throws NullPointerException 当 {@code engine} 为 {@code null}
     */
    public HarkenService(ScriptEngine engine,
                         BeanResolver beanResolver,
                         List<BindingContributor> contributors,
                         List<ScriptValidator> validators,
                         List<AuditStore> auditStores,
                         TransactionBridge transactionBridge,
                         HarkenOptions options,
                         WriteGuard writeGuard,
                         ScriptCatalog catalog) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.beanResolver = beanResolver == null ? BeanResolver.empty() : beanResolver;
        this.contributors = sortContributors(contributors);
        this.validators = sortValidators(validators);
        this.auditStores = auditStores == null || auditStores.isEmpty()
                ? List.of(new Slf4jAuditStore())
                : List.copyOf(auditStores);
        this.transactionBridge = transactionBridge == null
                ? TransactionBridge.NoopTransactionBridge.INSTANCE
                : transactionBridge;
        this.options = options == null ? HarkenOptions.defaults() : options;
        this.writeGuard = writeGuard == null ? WriteGuard.NONE : writeGuard;
        this.catalog = catalog == null ? NoopScriptCatalog.INSTANCE : catalog;
    }

    // ------------------------------------------------------------------
    // 两个通道。签名不同是刻意的：AI 通道没有 commit 参数。
    // ------------------------------------------------------------------

    /**
     * AI 通道：执行一段只读脚本，不记录操作人与 IP。
     *
     * <p>等价于 {@code execute(request, null, null)}。生产环境建议用带操作人的重载，
     * 否则审计记录里无法回答「谁跑的」。
     *
     * @param request 只读请求
     * @return 执行结果，永不返回 {@code null}；脚本异常也包装在结果里而不是抛出
     */
    public ScriptResult execute(ReadOnlyRequest request) {
        return doExecute(request, false, null, null);
    }

    /**
     * AI 通道：执行一段只读脚本。
     *
     * <p>无事务保护时，默认会直接拒绝（{@link HarkenOptions#requireTransactionForAi()}），
     * 因为 bean 方法内部的写操作没有回滚可兜。
     *
     * @param request  只读请求
     * @param operator 操作人，用于审计，可为 {@code null}
     * @param clientIp 客户端 IP，用于审计，可为 {@code null}
     * @return 执行结果，永不返回 {@code null}
     */
    public ScriptResult execute(ReadOnlyRequest request, String operator, String clientIp) {
        return doExecute(request, false, operator, clientIp);
    }

    /**
     * 人类通道：commit 由请求决定，但仍受事务可用性约束。
     *
     * <p>三种请求对应的三种事务语义：
     * <ul>
     *   <li>{@code commit=true} → {@code COMMIT}，无事务时返回
     *       {@code HARKEN_NO_TRANSACTION} 并拒绝执行</li>
     *   <li>{@code commit=false} → {@code ROLLBACK}（试跑：允许写、必定回滚），
     *       无事务时同样拒绝 —— 否则「报告已回滚、其实落了库」</li>
     * </ul>
     *
     * <p>拒绝而不是「执行完提示一句」：数据一旦改了且无法回滚，警告没有意义。
     *
     * @param request  读写请求
     * @param operator 操作人，用于审计
     * @param clientIp 客户端 IP，用于审计
     * @return 执行结果，永不返回 {@code null}
     */
    public ScriptResult execute(ReadWriteRequest request, String operator, String clientIp) {
        return doExecute(request, request.commit(), operator, clientIp);
    }

    /**
     * 干跑编译：只编译不执行，用于前端画语法红线。
     *
     * <p>编译产物会进入缓存，因此「先干跑再执行」不会编译两次。
     *
     * @param script Groovy 源码
     * @throws HarkenException.Compilation 语法错误，异常里带行列号
     */
    public void compileOnly(String script) {
        engine.check(script);
    }

    /**
     * 当前生效的执行参数。
     *
     * @return 不可变参数对象
     */
    public HarkenOptions options() {
        return options;
    }

    /**
     * Bean 来源。
     *
     * <p>暴露它是为了让补全元数据复用同一个解析器：容器里只该有一份
     * 「Bean 名 → 类型」的扫描结果，重复扫描既慢又可能出现两份不一致的表。
     *
     * @return 解析器，永不为 {@code null}
     */
    public BeanResolver beanResolver() {
        return beanResolver;
    }

    /**
     * 事务能力的可读描述，用于启动日志与 UI 状态条。
     *
     * @return 例如 {@code spring (JdbcTransactionManager)} 或 {@code none (no transaction protection)}
     */
    public String transactionDescription() {
        return transactionBridge.description();
    }

    /**
     * 当前环境是否具备事务保护。
     *
     * <p>返回 {@code false} 时，UI 必须显示醒目警告，且写操作会被拒绝。
     *
     * @return {@code true} 表示有可用事务管理器
     */
    public boolean transactionSupported() {
        return transactionBridge.isSupported();
    }

    /**
     * 审计后端的可读描述，用于启动日志与 UI 状态条。
     *
     * @return 逗号分隔的存储名，例如 {@code Slf4jAuditStore} 或 {@code MemoryAuditStore, Slf4jAuditStore}
     */
    public String auditDescription() {
        return auditStores.stream().map(AuditStore::name).collect(Collectors.joining(", "));
    }

    /**
     * 写入守卫的可读描述，用于启动日志与 UI 状态条。
     *
     * @return 例如 {@code rows<=1000, deny=[supply_order]}；未配置守卫时为
     *         {@code none (no affected-row limit)}
     */
    public String writeGuardDescription() {
        return writeGuard.description();
    }

    /**
     * 是否至少有一个审计存储支持反查。
     *
     * <p>给控制台判断「最近执行」面板要不要显示用。只写日志时它返回 {@code false}，
     * 界面就去掉那个面板 —— 比显示一个永远空着的面板诚实。
     *
     * @return {@code true} 表示 {@link #recentAudits(int)} 可能返回非空结果
     */
    public boolean auditQueryable() {
        return auditStores.stream().anyMatch(AuditStore::supportsQuery);
    }

    /**
     * 预置脚本目录。受限模式用它替代自由编辑器，UI 据此渲染脚本列表。
     *
     * <p>永不返回 {@code null}：未配置目录时返回 {@link NoopScriptCatalog}（空列表），
     * 调用方不必判空。
     *
     * @return 当前生效的目录
     */
    public ScriptCatalog catalog() {
        return catalog;
    }

    /**
     * 最近的审计记录，按时间倒序。
     *
     * <p>给控制台的「最近执行」面板与 AI 的反查能力用。取<b>第一个支持查询</b>的存储：
     * 多个存储之间合并没有意义（内存只有最近几百条、库里有全量，拼起来既重复又缺漏），
     * 想看哪个后端就把它排在前面。
     *
     * <p>没有可查询的存储时返回空列表 —— 调用方不必为此写分支。
     * 查询失败同样返回空列表并打 WARN：审计读不出来不该让整个面板报错。
     *
     * @param limit 最多返回条数，{@code <= 0} 返回空列表
     * @return 不可变列表，永不为 {@code null}
     */
    public List<AuditRecord> recentAudits(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        for (AuditStore store : auditStores) {
            if (!store.supportsQuery()) {
                continue;
            }
            try {
                return store.query(limit);
            } catch (RuntimeException e) {
                log.warn("Harken 审计查询失败，尝试下一个存储：{}", store.name(), e);
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------------

    private ScriptResult doExecute(ScriptRequest request, boolean commitRequested,
                                   String operator, String clientIp) {
        final long startNanos = System.nanoTime();
        final String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        final String script = request.script();
        final boolean aiReadOnly = !request.writable();
        final boolean commitWanted = request.writable() && commitRequested;

        // 三种模式的含义见 TransactionBridge.Mode。关键一点：
        // 人类的"试跑"是 ROLLBACK，不是 READ_ONLY —— 后者会把连接置成只读，
        // 在 MySQL/PostgreSQL 上 UPDATE 会直接失败，试跑就试不了。
        final TransactionBridge.Mode txMode = aiReadOnly
                ? TransactionBridge.Mode.READ_ONLY
                : (commitWanted ? TransactionBridge.Mode.COMMIT : TransactionBridge.Mode.ROLLBACK);

        // PrintWriter 直接写 StringWriter，不需要 flush 也能落内容
        final StringWriter buffer = new StringWriter();
        final PrintWriter out = new PrintWriter(buffer);

        String resultText = null;
        String error = null;
        String errorType = null;
        String resultDigest = null;
        boolean ok = true;

        // 守卫在 worker 线程里累加行数，结果从这里带回主线程写审计。
        // 用 AtomicLong 而不是 long[]：超时丢弃线程时，两个线程可能同时碰到它。
        final AtomicLong affectedRows = new AtomicLong(-1L);
        // 事务体是否真的进入过。用来区分「执行了但回滚」与「压根没执行」——
        // 后者报 rolledBack=true 也是假话，虽然方向是安全的。
        final AtomicBoolean transactionEntered = new AtomicBoolean(false);

        try {
            if (script.length() > options.maxScriptLength()) {
                throw new HarkenException.Rejected("HARKEN_SCRIPT_TOO_LONG",
                        "脚本长度 " + script.length() + " 超过上限 " + options.maxScriptLength(), -1);
            }

            for (ScriptValidator validator : validators) {
                ScriptValidator.ValidationError ve = validator.validate(request);
                if (ve != null) {
                    throw new HarkenException.Rejected(ve.code(), ve.message(), ve.line());
                }
            }

            if (commitWanted && !transactionBridge.isSupported()) {
                // SR-02：不是"提示一下"，是直接拒绝。只读脚本不受影响，所以不会卡住排查场景。
                throw new HarkenException.Rejected("HARKEN_NO_TRANSACTION",
                        "当前环境没有可用的事务管理器，写操作已被拒绝。"
                                + "只读脚本不受影响；如确需写库，请配置事务管理器，"
                                + "或显式开启 allow-commit-without-transaction 并自行承担数据风险。", -1);
            }

            if (!aiReadOnly && !commitWanted && !transactionBridge.isSupported()) {
                // 试跑承诺的是"跑完不落库"。没有事务时这个承诺无法兑现，
                // 脚本里的 UPDATE 会真的提交 —— 此时返回 rolledBack=true 就是骗人。
                throw new HarkenException.Rejected("HARKEN_NO_TRANSACTION",
                        "当前环境没有可用的事务管理器，试跑（commit=false）已被拒绝："
                                + "没有事务就无法保证改动不落库，执行它等于直接改数据。"
                                + "请配置事务管理器后重试。", -1);
            }

            if (aiReadOnly && options.requireTransactionForAi() && !transactionBridge.isSupported()) {
                // 无事务时"只读"是假的：bean 方法内部的写入不会被回滚。
                throw new HarkenException.Rejected("HARKEN_AI_READONLY_UNSAFE",
                        "当前环境没有事务保护，AI 通道的只读无法成立"
                                + "（orderService.markPaid() 这类方法内部的写入兜不住），已拒绝执行。", -1);
            }

            final ExecutionContext context =
                    new ExecutionContext(collectVariables(request, operator), beanResolver);

            // 事务包在 worker 线程内部：Spring 的事务是 ThreadLocal 绑定的，
            // 在外层开事务、子线程执行，脚本拿到的连接没有事务上下文。
            // 守卫同理 —— 它也是 ThreadLocal，必须和脚本在同一个线程上。
            Object value = executor.call(options.timeoutMs(), () -> {
                try (WriteGuard.Scope scope = writeGuard.begin(traceId)) {
                    try {
                        return transactionBridge.inTransaction(txMode, () -> {
                            transactionEntered.set(true);
                            return engine.run(context, script, out);
                        });
                    } finally {
                        // 无论成功还是被行数闸拦下，已经执行的影响行数都要带回审计：
                        // 「本来会改 200 行」比「失败了」有用得多
                        affectedRows.set(scope.affectedRows());
                    }
                }
            });

            resultText = stringify(value);
            if (resultText != null) {
                resultDigest = "sha256:" + Digests.shortSha256(resultText, 12)
                        + " len=" + resultText.length();
            }
        } catch (HarkenException.Rejected e) {
            ok = false;
            error = e.getMessage();
            errorType = e.code();
        } catch (HarkenException.Timeout e) {
            ok = false;
            error = e.getMessage();
            errorType = "TIMEOUT";
        } catch (HarkenException.Compilation e) {
            ok = false;
            error = e.getMessage();
            errorType = "COMPILATION";
        } catch (Throwable t) {
            ok = false;
            error = describe(t);
            errorType = t.getClass().getSimpleName();
        }

        // 超时时 worker 可能仍在写 buffer。StringWriter 内部是同步的 StringBuffer，
        // 读到的是"某个时刻的前缀"，不会崩，但内容可能不完整——这是可接受的。
        String output = truncateUtf8(buffer.toString(), options.outputMaxBytes());
        boolean truncated = false;

        // 错误信息在这里统一脱敏，而不是在每个 catch 里各写一次：
        // 异常消息与栈里最常出现的是 JDBC URL（jdbc:mysql://user:pass@host）和
        // 「Authorization: Bearer xxx」这类内容，它们是**唯一**会同时出现在
        // 返回值（给调用方看）和审计（长期留存）里的东西，两处必须一致。
        error = Redactor.redact(error);

        if (resultText != null && resultText.length() > options.resultMaxBytes()) {
            resultText = truncateUtf8(resultText, options.resultMaxBytes());
            truncated = true;
        }
        if (output != null && output.length() >= options.outputMaxBytes() - 4) {
            truncated = true;
        }

        String hint = truncated
                ? "结果或输出被截断。请增加筛选条件（where / limit / take）后重新执行；"
                  + "基于当前返回内容得出的结论可能不完整。"
                : null;

        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;

        // rolledBack 必须反映事实，不能由"用户没勾提交"推出来：
        // 没有事务能力时根本谈不上回滚，此时报 true 就是假的。
        // 第三种情况同样要算进来：请求了提交、但执行中途抛异常，事务其实已经回滚，
        // 若还报 false，审计就会把「没落库」记成「落库了」——方向安全，但依然是错的。
        boolean rolledBack = transactionBridge.isSupported()
                && transactionEntered.get()
                && (txMode != TransactionBridge.Mode.COMMIT || !ok);

        writeAudit(traceId, request, script, commitWanted, rolledBack, ok,
                errorType, error, resultDigest, durationMs, operator, clientIp, affectedRows.get());

        return new ScriptResult(ok, resultText, output, error, errorType,
                durationMs, rolledBack, truncated, hint, request.channel(), traceId, affectedRows.get());
    }

    private Map<String, Object> collectVariables(ScriptRequest request, String operator) {
        Map<String, Object> variables = new LinkedHashMap<>();
        BindingContributor.Context context =
                new BindingContributor.Context(request.script(), request.channel(), operator);
        for (BindingContributor contributor : contributors) {
            Map<String, Object> contributed = contributor.contribute(context);
            if (contributed != null && !contributed.isEmpty()) {
                variables.putAll(contributed);
            }
        }
        // 受限模式的参数：注入为名为 `params` 的 Map，脚本里用 params.orderCode 访问。
        // 自由编写模式 params() 是空 Map，这里跳过，不会污染用户脚本的命名空间。
        if (!request.params().isEmpty()) {
            variables.put("params", request.params());
        }
        return variables;
    }

    private void writeAudit(String traceId, ScriptRequest request, String script,
                            boolean commitWanted, boolean rolledBack, boolean ok,
                            String errorType, String error, String resultDigest,
                            long durationMs, String operator, String clientIp,
                            long affectedRows) {
        AuditRecord record = AuditRecord.builder()
                .traceId(traceId)
                .channel(request.channel())
                .operator(operator)
                .clientIp(clientIp)
                .scriptDigest("sha256:" + Digests.shortSha256(script, 16))
                // 摘要用原文算（同一段脚本必须得到同一个摘要），落库的正文脱敏。
                // 两者不对应是有意的：脱敏后的文本不该再被当作「脚本身份」。
                .script(truncateUtf8(Redactor.redact(script), options.maxScriptLength()))
                .commitRequested(commitWanted)
                .rolledBack(rolledBack)
                .ok(ok)
                .errorType(errorType)
                .errorMsg(error == null ? null
                        : truncateUtf8(Redactor.redact(error), MAX_AUDIT_ERROR_BYTES))
                .resultDigest(resultDigest)
                .durationMs(durationMs)
                .affectedRows(affectedRows)
                .build();

        for (AuditStore store : auditStores) {
            try {
                store.save(record);
            } catch (Throwable t) {
                // 审计失败绝不能拖垮主流程，但必须可见：静默丢记录是最危险的状态
                log.error("Harken 审计写入失败，该条记录已丢失。store={} trace={}",
                        store.name(), traceId, t);
            }
        }
    }

    private static String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence cs) {
            return cs.toString();
        }
        try {
            return String.valueOf(value);
        } catch (Throwable t) {
            // 懒加载代理、循环引用、自定义 toString 抛异常，都从这里兜住
            return "<toString 失败: " + t.getClass().getSimpleName() + ">";
        }
    }

    private static String describe(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return truncateUtf8(sw.toString(), MAX_ERROR_BYTES);
    }

    /**
     * 按 UTF-8 字节数截断，且不切断多字节字符（否则会得到半个汉字）。
     */
    static String truncateUtf8(String s, int maxBytes) {
        if (s == null) {
            return null;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        int end = Math.min(maxBytes, bytes.length - 1);
        // UTF-8 续字节高两位是 10，往回到字符边界
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static List<BindingContributor> sortContributors(List<BindingContributor> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<BindingContributor> copy = new ArrayList<>(items);
        copy.sort(Comparator.comparingInt(BindingContributor::getOrder));
        return List.copyOf(copy);
    }

    private static List<ScriptValidator> sortValidators(List<ScriptValidator> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<ScriptValidator> copy = new ArrayList<>(items);
        copy.sort(Comparator.comparingInt(ScriptValidator::getOrder));
        return List.copyOf(copy);
    }
}
