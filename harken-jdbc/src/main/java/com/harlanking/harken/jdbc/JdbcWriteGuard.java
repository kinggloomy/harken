package com.harlanking.harken.jdbc;

import com.harlanking.harken.core.HarkenException;
import com.harlanking.harken.core.spi.WriteGuard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JDBC 层的写入守卫：在执行过程中数影响行数、比关键表黑名单、拦 DDL。
 *
 * <h2>它在链路里的位置</h2>
 *
 * <pre>
 * 脚本 ──▶ orderService.markPaid() ──▶ JdbcTemplate ──▶ GuardedDataSource（本类装进去的代理）
 *                                                          │
 *                                     check(sql) ──────────┤ 命中黑名单/DDL → 抛 HarkenException.Rejected
 *                                     count(rows) ─────────┘ 累计超上限 → 抛 HarkenException.Rejected
 *                                                                     │
 *                                                          事务收到异常 → 回滚
 * </pre>
 *
 * <p>抛出的异常是 {@link HarkenException.Rejected}，它的 {@code code} 会原样变成
 * {@code ScriptResult.errorType}，所以前端与 AI 拿到的错误码是
 * {@code HARKEN_ROW_LIMIT} / {@code HARKEN_TABLE_FORBIDDEN} / {@code HARKEN_DDL_FORBIDDEN}，
 * 而不是一句笼统的 500。
 *
 * <h2>为什么计数放在线程上</h2>
 *
 * <p>一次脚本执行可能跨越多个 Bean、多条 SQL、多层事务模板。<b>执行</b>这条线是线程，
 * 所以计数也挂在 {@code ThreadLocal} 上：{@link #begin(String)} 在脚本线程打开作用域，
 * 期间任何经过代理的写入都会累加，{@link Scope#close()} 清除。
 *
 * <p>作用域外的 SQL（应用自己的定时任务、Spring 初始化脚本）完全直通 ——
 * 守卫只对「脚本引起的写入」负责，这一点很容易被误解，特此写明。
 *
 * <h2>它不是安全边界</h2>
 *
 * <p>见 {@link SqlInspector} 的已知缺口。它防手滑，不防入侵。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class JdbcWriteGuard implements WriteGuard {

    /** 命中关键表黑名单。 */
    public static final String CODE_TABLE_FORBIDDEN = "HARKEN_TABLE_FORBIDDEN";

    /** 影响行数超过上限。 */
    public static final String CODE_ROW_LIMIT = "HARKEN_ROW_LIMIT";

    /** 策略不允许 DDL。 */
    public static final String CODE_DDL_FORBIDDEN = "HARKEN_DDL_FORBIDDEN";

    private static final int CACHE_CAPACITY = 256;

    private final GuardPolicy policy;
    private final SqlInspector inspector = new SqlInspector();

    /**
     * SQL 文本 → 分析结果。循环里执行同一条 SQL（上千次）不该解析上千次。
     * 用访问序 LRU，容量固定，不会无限增长。
     */
    private final Map<String, SqlInspector.Analysis> cache =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SqlInspector.Analysis> eldest) {
                    return size() > CACHE_CAPACITY;
                }
            });

    private final ThreadLocal<Counter> active = new ThreadLocal<>();

    /**
     * @param policy 策略，不能为 {@code null}
     */
    public JdbcWriteGuard(GuardPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        this.policy = policy;
    }

    /**
     * @return 当前策略
     */
    public GuardPolicy policy() {
        return policy;
    }

    /**
     * 打开一次执行的作用域。由 {@code HarkenService} 在脚本线程上调用。
     *
     * @param traceId 追踪 ID，仅用于错误信息与日志
     * @return 作用域，必须 close
     */
    @Override
    public Scope begin(String traceId) {
        Counter previous = active.get();
        Counter counter = new Counter(policy, traceId);
        active.set(counter);
        return new ActiveScope(counter, previous);
    }

    /**
     * @return 策略的可读描述，进启动日志
     */
    @Override
    public String description() {
        return policy.describe();
    }

    // ------------------------------------------------------------------
    // 给代理调用的钩子。作用域外一律直通。
    // ------------------------------------------------------------------

    /**
     * 语句即将执行：黑名单与 DDL 检查。
     *
     * @param sql SQL 文本，可为 {@code null}
     */
    void check(String sql) {
        Counter counter = active.get();
        if (counter != null) {
            counter.check(sql);
        }
    }

    /**
     * 语句执行完成：累加影响行数，超限抛异常。
     *
     * @param sql   SQL 文本，可为 {@code null}
     * @param rows  实际影响行数，{@code <= 0} 忽略
     */
    void count(String sql, long rows) {
        Counter counter = active.get();
        if (counter != null) {
            counter.count(sql, rows);
        }
    }

    /**
     * 当前线程是否处在一次脚本执行中。代理用它跳过不必要的解析。
     *
     * @return {@code true} 表示有活动作用域
     */
    boolean isActive() {
        return active.get() != null;
    }

    SqlInspector.Analysis analysisOf(String sql) {
        SqlInspector.Analysis cached = cache.get(sql);
        if (cached != null) {
            return cached;
        }
        SqlInspector.Analysis analyzed = inspector.analyze(sql);
        cache.put(sql, analyzed);
        return analyzed;
    }

    // ------------------------------------------------------------------

    /**
     * 一次执行的计数器。
     */
    private final class Counter {

        private final GuardPolicy policy;
        private final String traceId;
        private long rows;

        private Counter(GuardPolicy policy, String traceId) {
            this.policy = policy;
            this.traceId = traceId;
        }

        private void check(String sql) {
            if (sql == null || sql.isBlank()) {
                return;
            }
            SqlInspector.Analysis analysis = analysisOf(sql);
            if (!analysis.writes()) {
                return;
            }
            if (analysis.kind() == SqlInspector.Kind.DDL && policy.denyDdl()) {
                throw new HarkenException.Rejected(CODE_DDL_FORBIDDEN,
                        "DDL 已被拒绝：" + preview(sql)
                                + "。当前策略不允许脚本改动表结构（harken.guard.deny-ddl=false 可放开）。"
                                + "语句未执行，数据与结构均未改动。", -1);
            }
            for (String identifier : analysis.identifiers()) {
                if (policy.denies(identifier)) {
                    throw new HarkenException.Rejected(CODE_TABLE_FORBIDDEN,
                            "写入已被拒绝：语句涉及关键表 " + identifier + "（harken.guard.deny-tables）。"
                                    + "语句未执行，数据未改动。需要改这张表，请走应用自身的业务流程。", -1);
                }
            }
        }

        private void count(String sql, long delta) {
            if (delta <= 0) {
                return;
            }
            rows += delta;
            if (policy.rowLimitEnabled() && rows > policy.maxAffectedRows()) {
                throw new HarkenException.Rejected(CODE_ROW_LIMIT,
                        "影响行数超过上限：本次执行已累计 " + rows + " 行，上限 "
                                + policy.maxAffectedRows() + " 行"
                                + (sql == null ? "" : "（" + preview(sql) + "）")
                                + "。事务即将回滚，数据未改动。"
                                + "请加上 where / limit 分批执行，或由管理员调整 "
                                + "harken.guard.max-affected-rows。", -1);
            }
        }

        private long rows() {
            return rows;
        }

        private String traceId() {
            return traceId;
        }
    }

    /**
     * 作用域句柄：关闭时恢复上一层状态（支持嵌套调用）。
     */
    private final class ActiveScope implements Scope {

        private final Counter counter;
        private final Counter previous;
        private boolean closed;

        private ActiveScope(Counter counter, Counter previous) {
            this.counter = counter;
            this.previous = previous;
        }

        @Override
        public long affectedRows() {
            return counter.rows();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                active.remove();
            } else {
                active.set(previous);
            }
        }

        /** @return 追踪 ID，调试用 */
        @Override
        public String toString() {
            return "JdbcWriteGuard.Scope(trace=" + counter.traceId() + ", rows=" + counter.rows() + ")";
        }
    }

    private static String preview(String sql) {
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }
}
