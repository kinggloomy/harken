package com.harlanking.harken.core;

import com.harlanking.harken.core.api.ReadOnlyRequest;
import com.harlanking.harken.core.api.ReadWriteRequest;
import com.harlanking.harken.core.api.ScriptResult;
import com.harlanking.harken.core.impl.NoopAuditStore;
import com.harlanking.harken.core.spi.TransactionBridge;

import java.util.function.Supplier;

/**
 * 可运行的演示。跑法：
 *
 * <pre>
 * mvn -q test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.harlanking.harken.core.DemoMain -Dexec.classpathScope=test
 * </pre>
 */
public final class DemoMain {

    public static class OrderService {
        public int count() {
            return 42;
        }

        public String markPaid(long id) {
            return "PAID-" + id;
        }
    }

    public static class UserService {
        public String name() {
            return "harlan";
        }
    }

    /** 假装有事务。真实场景由 Spring 的 TransactionTemplate 实现。 */
    static final class DemoTx implements TransactionBridge {
        @Override
        public <T> T inTransaction(Mode mode, Supplier<T> work) {
            return work.get();
        }

        @Override public boolean isSupported() { return true; }
        @Override public String description() { return "demo-tx (READ_ONLY/ROLLBACK 都回滚)"; }
    }

    public static void main(String[] args) {
        HarkenService harken = HarkenRuntime.builder()
                .bean("orderService", new OrderService())
                .bean("userService", new UserService())
                .transactionBridge(new DemoTx())
                .auditStore(NoopAuditStore.INSTANCE)
                .options(HarkenOptions.defaults().withTimeoutMs(500).withResultMaxBytes(120))
                .build();

        section("1 · 返回值与 println 分离");
        show(harken, "println 'hello from script'\nprintln 'second line'\nreturn 1 + 1");

        section("2 · Bean 名直连（不需要 ctx.getBean）");
        show(harken, "return orderService.count()");

        section("3 · 变量注入 + 字符串插值");
        show(harken, "def who = userService.name()\nreturn \"orders=${orderService.count()} user=$who\"");

        section("4 · 只读通道拦截进程操作（AI 通道恒只读）");
        show(harken, "return Runtime.getRuntime().availableProcessors()");

        section("5 · 编译错误返回行列号（前端画红线用）");
        show(harken, "def x = \nreturn 1");

        section("6 · 死循环被 500ms 超时切断");
        long start = System.currentTimeMillis();
        show(harken, "def i = 0\nwhile (true) { i = i + 1 }");
        System.out.println("    实际耗时: " + (System.currentTimeMillis() - start) + "ms");

        section("7 · 结果超限截断 + 提示");
        show(harken, "return 'x' * 10000");

        section("8 · 人类通道只读执行（commit=false）");
        ScriptResult rw = harken.execute(ReadWriteRequest.dryRun("return orderService.markPaid(7)"),
                "harlan", "10.0.1.5");
        System.out.println("    channel=" + rw.channel() + " rolledBack=" + rw.rolledBack()
                + " result=" + rw.result());

        section("9 · 无事务环境：AI 通道拒绝执行（只读无法保证）");
        HarkenService noTx = HarkenRuntime.builder()
                .bean("orderService", new OrderService())
                .transactionBridge(TransactionBridge.NoopTransactionBridge.INSTANCE)
                .auditStore(NoopAuditStore.INSTANCE)
                .build();
        show(noTx, "return orderService.count()");

        section("10 · 无事务环境：人类通道 commit 请求被拒绝");
        ScriptResult noTxCommit = noTx.execute(new ReadWriteRequest("return 1", true), "harlan", "10.0.1.5");
        System.out.println("    ok=" + noTxCommit.ok() + " errorType=" + noTxCommit.errorType());
        System.out.println("    " + noTxCommit.error());
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void show(HarkenService harken, String script) {
        ScriptResult r = harken.execute(new ReadOnlyRequest(script), "tester", "127.0.0.1");
        System.out.println("    ok        = " + r.ok());
        System.out.println("    result    = " + oneLine(r.result()));
        System.out.println("    output    = " + oneLine(r.output()));
        if (!r.ok()) {
            System.out.println("    errorType = " + r.errorType());
            System.out.println("    error     = " + oneLine(firstLine(r.error())));
        }
        System.out.println("    truncated = " + r.truncated()
                + (r.hint() == null ? "" : "  hint=" + r.hint()));
        System.out.println("    ms        = " + r.durationMs() + "  rolledBack=" + r.rolledBack());
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "null";
        }
        String v = s.replace("\r\n", "\\n").replace("\n", "\\n");
        return v.length() > 100 ? v.substring(0, 100) + "..." : v;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return null;
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
