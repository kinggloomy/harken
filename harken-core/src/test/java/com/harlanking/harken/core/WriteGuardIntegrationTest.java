package com.harlanking.harken.core;

import com.harlanking.harken.core.api.ReadOnlyRequest;
import com.harlanking.harken.core.api.ReadWriteRequest;
import com.harlanking.harken.core.api.ScriptResult;
import com.harlanking.harken.core.impl.MapBeanResolver;
import com.harlanking.harken.core.spi.AuditRecord;
import com.harlanking.harken.core.spi.AuditStore;
import com.harlanking.harken.core.spi.TransactionBridge;
import com.harlanking.harken.core.spi.WriteGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HarkenService} 与 {@link WriteGuard} 的接线。
 *
 * <p>这里验的不是「守卫能不能数行数」（那是 {@code harken-jdbc} 的事），
 * 而是三个<b>时序与线程</b>上的契约 —— 它们错了，行数闸会在生产上静默失效：
 *
 * <ol>
 *   <li>作用域必须开在<b>跑脚本的那个线程</b>上（守卫普遍用 ThreadLocal）；</li>
 *   <li>作用域必须在异常路径上也被关闭，否则线程池里的线程会带着脏状态跑下一条脚本；</li>
 *   <li>行数必须进审计 —— 尤其「本来会改 200 行、被拦下」那一次。</li>
 * </ol>
 */
class WriteGuardIntegrationTest {

    /** 记录线程名、是否关闭、以及报告的行数。 */
    static final class RecordingGuard implements WriteGuard {

        final List<String> beginThreads = new CopyOnWriteArrayList<>();
        final List<Long> reportedRows = new CopyOnWriteArrayList<>();
        int closed = 0;
        long rowsToReport = -1L;

        @Override
        public Scope begin(String traceId) {
            beginThreads.add(Thread.currentThread().getName());
            return new Scope() {
                @Override
                public long affectedRows() {
                    reportedRows.add(rowsToReport);
                    return rowsToReport;
                }

                @Override
                public void close() {
                    closed++;
                }
            };
        }

        @Override
        public String description() {
            return "recording-guard";
        }
    }

    /** 记录审计记录。 */
    static final class RecordingAudit implements AuditStore {
        final List<AuditRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void save(AuditRecord record) {
            records.add(record);
        }
    }

    /** 会记录「自己在哪个线程上被调用」的 Bean。 */
    public static class ThreadProbe {
        static volatile String lastCaller = "";

        public String whoAmI() {
            lastCaller = Thread.currentThread().getName();
            return lastCaller;
        }

        /**
         * 模拟「JDBC 守卫在语句执行中途抛错」：异常必须能从脚本里穿出来。
         */
        public String boom() {
            throw new HarkenException.Rejected("HARKEN_ROW_LIMIT", "累计 200 行，上限 100", -1);
        }
    }

    /** 有事务能力的替身。 */
    static final class PassThroughTx implements TransactionBridge {
        Mode mode;

        @Override
        public <T> T inTransaction(Mode mode, Supplier<T> work) {
            this.mode = mode;
            return work.get();
        }
    }

    private static final class Fixture {
        final RecordingGuard guard = new RecordingGuard();
        final RecordingAudit audit = new RecordingAudit();
        final PassThroughTx tx = new PassThroughTx();
        final HarkenService service;

        Fixture() {
            service = HarkenRuntime.builder()
                    .beanResolver(new MapBeanResolver(Map.of("probe", new ThreadProbe())))
                    .transactionBridge(tx)
                    .auditStore(audit)
                    .writeGuard(guard)
                    .build();
        }
    }

    @Test
    @DisplayName("守卫的作用域开在脚本线程上，不是调用方线程 —— 否则 ThreadLocal 型的守卫全失效")
    void scopeIsOpenedOnTheScriptThread() {
        Fixture f = new Fixture();

        ScriptResult r = f.service.execute(new ReadWriteRequest("probe.whoAmI()", false), "harlan", "127.0.0.1");

        assertTrue(r.ok(), r.error());
        assertEquals(1, f.guard.beginThreads.size());
        assertEquals(ThreadProbe.lastCaller, f.guard.beginThreads.get(0),
                "begin() 必须和脚本在同一个线程上执行");
        assertNotEquals(Thread.currentThread().getName(), f.guard.beginThreads.get(0),
                "如果相等，说明守卫开在了调用方线程上，脚本里的 JDBC 根本看不见它");
    }

    @Test
    @DisplayName("守卫报告的行数进入审计记录")
    void affectedRowsReachAudit() {
        Fixture f = new Fixture();
        f.guard.rowsToReport = 7L;

        f.service.execute(new ReadWriteRequest("return 1", false), "harlan", "127.0.0.1");

        assertEquals(1, f.audit.records.size());
        assertEquals(7L, f.audit.records.get(0).affectedRows());
    }

    @Test
    @DisplayName("没有守卫时审计里是 -1（未测量），不是 0（测了但没改）")
    void noGuardMeansUnknownNotZero() {
        RecordingAudit audit = new RecordingAudit();
        HarkenService service = HarkenRuntime.builder()
                .beanResolver(new MapBeanResolver(Map.of("probe", new ThreadProbe())))
                .transactionBridge(new PassThroughTx())
                .auditStore(audit)
                .build();

        service.execute(new ReadWriteRequest("return 1", false), "harlan", "127.0.0.1");

        assertEquals(-1L, audit.records.get(0).affectedRows());
    }

    @Test
    @DisplayName("守卫在脚本内部抛异常时：作用域照样关闭，错误码原样透出，且记为已回滚")
    void guardExceptionIsReportedAndScopeClosed() {
        Fixture f = new Fixture();
        WriteGuard failing = traceId -> new WriteGuard.Scope() {
            @Override
            public long affectedRows() {
                return 200L;
            }

            @Override
            public void close() {
                f.guard.closed++;
            }
        };
        HarkenService service = HarkenRuntime.builder()
                .beanResolver(new MapBeanResolver(Map.of("probe", new ThreadProbe())))
                .transactionBridge(f.tx)
                .auditStore(f.audit)
                .writeGuard(failing)
                .build();

        // 请求提交，但脚本里的 Bean 在写到第 200 行时被守卫拦下
        ScriptResult r = service.execute(new ReadWriteRequest("probe.boom()", true), "harlan", "127.0.0.1");

        assertFalse(r.ok());
        assertEquals("HARKEN_ROW_LIMIT", r.errorType());
        assertTrue(r.rolledBack(), "请求了提交但中途抛异常，事务实际已回滚，结果必须如实写 true");
        assertEquals(1, f.guard.closed, "异常路径上 close() 也必须被调用，否则线程池会带着脏状态");

        AuditRecord record = f.audit.records.get(0);
        assertEquals("HARKEN_ROW_LIMIT", record.errorType());
        assertEquals(200L, record.affectedRows(), "被拦下的那次最需要记录「本来会改多少行」");
        assertTrue(record.rolledBack());
    }

    @Test
    @DisplayName("AI 只读通道也开作用域：行数闸对 AI 同样生效")
    void aiChannelAlsoGetsScope() {
        Fixture f = new Fixture();

        f.service.execute(new ReadOnlyRequest("return 1"), "ai-agent", "127.0.0.1");

        assertEquals(1, f.guard.beginThreads.size());
        assertEquals(TransactionBridge.Mode.READ_ONLY, f.tx.mode);
        assertEquals("ai", f.audit.records.get(0).channel());
    }

    @Test
    @DisplayName("description 透传，用于启动日志")
    void descriptionIsExposed() {
        Fixture f = new Fixture();

        assertEquals("recording-guard", f.service.writeGuardDescription());
        assertEquals("none (no affected-row limit)",
                HarkenRuntime.builder().build().writeGuardDescription());
    }
}
