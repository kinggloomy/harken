package com.harlanking.harken.core;

import com.harlanking.harken.core.api.ReadOnlyRequest;
import com.harlanking.harken.core.api.ReadWriteRequest;
import com.harlanking.harken.core.api.ScriptResult;
import com.harlanking.harken.core.impl.NoopAuditStore;
import com.harlanking.harken.core.spi.BeanResolver;
import com.harlanking.harken.core.spi.TransactionBridge;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心行为的验证集。每个用例对应计划里的一条验收项。
 *
 * <p>跑法：{@code mvn -pl harken-core test}
 */
class HarkenServiceTest {

    // ------------------------------------------------------------------
    // 被测对象与替身
    // ------------------------------------------------------------------

    public static class FakeOrderService {
        public int count() {
            return 42;
        }

        public String markPaid(long id) {
            return "PAID-" + id;
        }
    }

    /** 记录 get() 调用次数，用来验证"懒解析"。 */
    static final class CountingResolver implements BeanResolver {
        private final Map<String, Object> beans = new LinkedHashMap<>();
        int getCalls = 0;

        CountingResolver put(String name, Object instance) {
            beans.put(name, instance);
            return this;
        }

        @Override public boolean contains(String name) { return beans.containsKey(name); }

        @Override public Object get(String name) {
            getCalls++;
            return beans.get(name);
        }

        @Override public Class<?> typeOf(String name) {
            Object v = beans.get(name);
            return v == null ? null : v.getClass();
        }

        @Override public Set<String> names() { return beans.keySet(); }
    }

    /** 有事务能力的替身。 */
    static final class SupportedTx implements TransactionBridge {
        Mode lastMode;
        int calls = 0;

        @Override
        public <T> T inTransaction(Mode mode, Supplier<T> work) {
            lastMode = mode;
            calls++;
            return work.get();
        }

        @Override public boolean isSupported() { return true; }
        @Override public String description() { return "test-tx"; }
    }

    private static HarkenService service(BeanResolver resolver, TransactionBridge tx) {
        return service(resolver, tx, HarkenOptions.defaults());
    }

    private static HarkenService service(BeanResolver resolver, TransactionBridge tx, HarkenOptions options) {
        return HarkenRuntime.builder()
                .beanResolver(resolver)
                .transactionBridge(tx)
                .options(options)
                .auditStore(NoopAuditStore.INSTANCE)
                .build();
    }

    private static CountingResolver orderResolver() {
        return new CountingResolver().put("orderService", new FakeOrderService());
    }

    // ------------------------------------------------------------------
    // 基础执行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("return 值被字符串化后返回")
    void returnsValue() {
        ScriptResult r = service(orderResolver(), new SupportedTx())
                .execute(new ReadOnlyRequest("return 1 + 1"), "tester", "127.0.0.1");

        assertTrue(r.ok(), r.error());
        assertEquals("2", r.result());
        assertEquals("ai", r.channel());
        assertTrue(r.rolledBack());
    }

    @Test
    @DisplayName("println 被捕获到 output，不落到宿主控制台")
    void capturesPrintln() {
        ScriptResult r = service(orderResolver(), new SupportedTx())
                .execute(new ReadOnlyRequest("println 'hello'\nprintln 42\nreturn 'done'"), "tester", "127.0.0.1");

        assertTrue(r.ok(), r.error());
        assertTrue(r.output().contains("hello"), "实际输出：" + r.output());
        assertTrue(r.output().contains("42"));
        assertEquals("done", r.result());
    }

    // ------------------------------------------------------------------
    // Bean 直连与懒解析
    // ------------------------------------------------------------------

    @Test
    @DisplayName("脚本里直接用 bean 名，不需要 ctx.getBean")
    void resolvesBeanByName() {
        ScriptResult r = service(orderResolver(), new SupportedTx())
                .execute(new ReadOnlyRequest("orderService.count()"), "tester", "127.0.0.1");

        assertTrue(r.ok(), r.error());
        assertEquals("42", r.result());
    }

    @Test
    @DisplayName("脚本没用到 bean 时，不触发解析（懒解析）")
    void doesNotResolveUnusedBean() {
        CountingResolver resolver = orderResolver();
        ScriptResult r = service(resolver, new SupportedTx())
                .execute(new ReadOnlyRequest("return 1"), "tester", "127.0.0.1");

        assertTrue(r.ok(), r.error());
        assertEquals(0, resolver.getCalls, "未使用的 bean 不应被解析");
    }

    @Test
    @DisplayName("同一脚本内多次使用同一 bean，只解析一次")
    void resolvesBeanOncePerRun() {
        CountingResolver resolver = orderResolver();
        ScriptResult r = service(resolver, new SupportedTx())
                .execute(new ReadOnlyRequest("orderService.count(); orderService.count(); return 1"),
                        "tester", "127.0.0.1");

        assertTrue(r.ok(), r.error());
        assertEquals(1, resolver.getCalls);
    }

    // ------------------------------------------------------------------
    // 通道隔离
    // ------------------------------------------------------------------

    @Test
    @DisplayName("无事务时 AI 通道直接拒绝（只读无法保证）")
    void aiChannelRejectsWhenNoTransaction() {
        ScriptResult r = service(orderResolver(), TransactionBridge.NoopTransactionBridge.INSTANCE)
                .execute(new ReadOnlyRequest("return 1"), "tester", "127.0.0.1");

        assertFalse(r.ok());
        assertEquals("HARKEN_AI_READONLY_UNSAFE", r.errorType());
    }

    @Test
    @DisplayName("AI 通道以 READ_ONLY 打开事务")
    void aiChannelOpensReadOnlyTransaction() {
        SupportedTx tx = new SupportedTx();
        ScriptResult r = service(orderResolver(), tx)
                .execute(new ReadOnlyRequest("return 1"), "tester", "127.0.0.1");

        assertTrue(r.ok(), r.error());
        assertEquals(TransactionBridge.Mode.READ_ONLY, tx.lastMode);
        assertTrue(r.rolledBack());
    }

    @Test
    @DisplayName("人类试跑（commit=false）必须是 ROLLBACK 而不是 READ_ONLY：置只读会让 UPDATE 直接失败")
    void humanDryRunUsesRollbackMode() {
        SupportedTx tx = new SupportedTx();
        ScriptResult r = service(orderResolver(), tx)
                .execute(ReadWriteRequest.dryRun("orderService.markPaid(1)"), "tester", "127.0.0.1");

        assertTrue(r.ok(), r.error());
        assertEquals(TransactionBridge.Mode.ROLLBACK, tx.lastMode,
                "试跑要能写才能看影响行数，设成 READ_ONLY 在 MySQL/PG 上会直接报错");
        assertTrue(r.rolledBack());
    }

    @Test
    @DisplayName("人类提交以 COMMIT 打开事务，且不报告回滚")
    void humanCommitUsesCommitMode() {
        SupportedTx tx = new SupportedTx();
        ScriptResult r = service(orderResolver(), tx)
                .execute(new ReadWriteRequest("orderService.markPaid(1)", true), "tester", "127.0.0.1");

        assertTrue(r.ok(), r.error());
        assertEquals(TransactionBridge.Mode.COMMIT, tx.lastMode);
        assertFalse(r.rolledBack());
    }

    @Test
    @DisplayName("无事务时人类通道的 commit 请求被拒绝")
    void humanCommitRejectedWithoutTransaction() {
        ScriptResult r = service(orderResolver(), TransactionBridge.NoopTransactionBridge.INSTANCE)
                .execute(new ReadWriteRequest("return 1", true), "tester", "127.0.0.1");

        assertFalse(r.ok());
        assertEquals("HARKEN_NO_TRANSACTION", r.errorType());
    }

    @Test
    @DisplayName("无事务时人类通道的试跑（commit=false）必须拒绝：否则脚本真写库却报告已回滚")
    void humanDryRunRejectedWithoutTransaction() {
        ScriptResult r = service(orderResolver(), TransactionBridge.NoopTransactionBridge.INSTANCE)
                .execute(ReadWriteRequest.dryRun("orderService.markPaid(1)"), "tester", "127.0.0.1");

        assertFalse(r.ok(), "无事务时无法保证不落库，必须拒绝而不是执行完报 rolledBack=true");
        assertEquals("HARKEN_NO_TRANSACTION", r.errorType());
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("只读执行拦截进程操作")
    void blocksProcessApiInReadonly() {
        ScriptResult r = service(orderResolver(), new SupportedTx())
                .execute(new ReadOnlyRequest("Runtime.getRuntime().availableProcessors()"),
                        "tester", "127.0.0.1");

        assertFalse(r.ok());
        assertEquals("HARKEN_FORBIDDEN_API", r.errorType());
    }

    @Test
    @DisplayName("拦截编译期执行注解 @Grab（对所有人都拦，含人类通道）")
    void blocksCompileTimeAnnotation() {
        ScriptResult r = service(orderResolver(), new SupportedTx())
                .execute(new ReadWriteRequest("@Grab('x:y:1.0')\nreturn 1", true),
                        "tester", "127.0.0.1");

        assertFalse(r.ok());
        assertEquals("HARKEN_COMPILE_TIME_EVAL", r.errorType());
    }

    // ------------------------------------------------------------------
    // 失败路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("语法错误返回 COMPILATION 且带行号")
    void compilationErrorCarriesLineNumber() {
        ScriptResult r = service(orderResolver(), new SupportedTx())
                .execute(new ReadOnlyRequest("def x = \nreturn 1"), "tester", "127.0.0.1");

        assertFalse(r.ok());
        assertEquals("COMPILATION", r.errorType());
        assertTrue(r.error() != null && !r.error().isBlank());
    }

    @Test
    @DisplayName("死循环被超时切断")
    void timesOutOnInfiniteLoop() {
        HarkenOptions options = HarkenOptions.defaults().withTimeoutMs(300);
        long start = System.currentTimeMillis();

        ScriptResult r = service(orderResolver(), new SupportedTx(), options)
                .execute(new ReadOnlyRequest("def i = 0\nwhile (true) { i = i + 1 }"),
                        "tester", "127.0.0.1");

        long elapsed = System.currentTimeMillis() - start;
        assertFalse(r.ok());
        assertEquals("TIMEOUT", r.errorType());
        assertTrue(elapsed < 3000, "超时未生效，耗时 " + elapsed + "ms");
    }

    @Test
    @DisplayName("结果超限被截断并给出提示")
    void truncatesLargeResult() {
        HarkenOptions options = HarkenOptions.defaults().withResultMaxBytes(100);
        ScriptResult r = service(orderResolver(), new SupportedTx(), options)
                .execute(new ReadOnlyRequest("return 'x' * 10000"), "tester", "127.0.0.1");

        assertTrue(r.ok(), r.error());
        assertTrue(r.truncated());
        assertTrue(r.hint() != null && r.hint().contains("截断"));
        assertTrue(r.result().length() <= 100);
    }

    @Test
    @DisplayName("脚本抛异常时，异常堆栈进 error，事务仍回滚")
    void scriptExceptionIsCaptured() {
        ScriptResult r = service(orderResolver(), new SupportedTx())
                .execute(new ReadOnlyRequest("throw new IllegalStateException('boom')"),
                        "tester", "127.0.0.1");

        assertFalse(r.ok());
        assertEquals("IllegalStateException", r.errorType());
        assertTrue(r.error().contains("boom"));
        assertTrue(r.rolledBack());
    }
}
