package com.harlanking.harken.core.spi;

import java.util.function.Supplier;

/**
 * 事务桥。core 不认识 Spring，只认识这个接口。
 *
 * <h2>为什么参数是三种模式，而不是一个 {@code readOnly} 布尔</h2>
 *
 * <p>最早这里只有 {@code boolean readOnly}，把两件事混成了一件：
 * 「严格只读」和「试跑但允许写、最后回滚」。后果是后者把数据库连接也置成了只读，
 * 于是当人想「先跑一次看影响几行、再决定要不要提交」时，
 * MySQL / PostgreSQL 会直接抛 {@code cannot execute UPDATE in a read-only transaction}
 * —— 整个「试跑」卖点在最常见的两个数据库上失效。
 *
 * <p>H2 恰好忽略 {@code Connection.setReadOnly(true)}，所以这个问题在 demo 里看不出来。
 * 一个只在 H2 上成立的承诺不是承诺，因此拆成三个模式。
 *
 * <h2>实现必须满足的契约</h2>
 * <table border="1">
 *   <caption>模式与实现义务</caption>
 *   <tr><th>模式</th><th>数据库层</th><th>结束动作</th></tr>
 *   <tr><td>{@link Mode#READ_ONLY}</td><td>连接置只读</td><td>必定回滚</td></tr>
 *   <tr><td>{@link Mode#ROLLBACK}</td><td>可写</td><td>必定回滚</td></tr>
 *   <tr><td>{@link Mode#COMMIT}</td><td>可写</td><td>提交</td></tr>
 * </table>
 *
 * <p><b>调用点很关键：</b> {@link #inTransaction} 必须在<b>执行脚本的那个线程</b>里调用。
 * Spring 的事务是 ThreadLocal 绑定的，在父线程开事务、子线程跑脚本，
 * 脚本里拿到的连接根本没有事务上下文，回滚不会生效。
 *
 * @author harlan
 * @since 1.0.0
 */
public interface TransactionBridge {

    /**
     * 一次执行的事务语义。
     *
     * @author harlan
     * @since 1.0.0
     */
    enum Mode {

        /**
         * 严格只读：连接/会话层面禁止写，结束时必定回滚。
         *
         * <p>用于 AI 通道。数据库真的拒绝写入，比「写完再回滚」更硬，
         * 因为有些副作用（序列、触发器里的外部调用）回滚不了。
         */
        READ_ONLY,

        /**
         * 允许写，结束时必定回滚。
         *
         * <p>用于人类的「试跑」：脚本可以执行 UPDATE，能看到影响行数，
         * 但事务结束就没了。这是「能回滚」这个卖点的实际载体。
         *
         * <p>注意这里<b>不能</b>把连接设成只读 —— 那会让 UPDATE 直接失败，
         * 试跑就变成了「试不了」。
         */
        ROLLBACK,

        /**
         * 允许写并提交。只应由人类显式确认后使用。
         */
        COMMIT
    }

    /**
     * 把一次执行包进事务。
     *
     * @param mode 事务语义，不能为 {@code null}
     * @param work 实际工作，不能为 {@code null}
     * @param <T>  返回值类型
     * @return 工作单元的返回值
     * @throws RuntimeException 工作单元抛出的异常（同时触发事务回滚）
     */
    <T> T inTransaction(Mode mode, Supplier<T> work);

    /**
     * 当前环境是否有事务保护。
     *
     * <p>返回 {@code false} 时上层必须<b>拒绝</b> {@link Mode#COMMIT} 与
     * {@link Mode#ROLLBACK} 两种请求，而不是「提示一下继续执行」——
     * 数据改了又撤不回来，警告没有意义；而「试跑」报告已回滚、实际落了库，
     * 比直接报错危险得多。
     *
     * @return {@code true} 表示可以保护写入
     */
    default boolean isSupported() {
        return true;
    }

    /**
     * 面向启动日志与 UI 状态条的可读描述。
     *
     * @return 例如 {@code spring (JdbcTransactionManager)}
     */
    default String description() {
        return getClass().getSimpleName();
    }

    /**
     * 没有事务能力时的兜底实现：不做任何包装，直接执行。
     *
     * <p>{@link #isSupported()} 恒为 {@code false}，因此上层会拒绝所有
     * {@link Mode#COMMIT} 与 {@link Mode#ROLLBACK} 请求。
     *
     * <p>本类对这两种模式<b>主动抛异常</b>，而不是「反正上层会挡」就默默执行：
     * 万一哪天调用方漏了检查，抛异常至少不会造成「以为回滚了、其实写进去了」。
     *
     * @author harlan
     * @since 1.0.0
     */
    final class NoopTransactionBridge implements TransactionBridge {

        /** 全局唯一实例，无状态。 */
        public static final NoopTransactionBridge INSTANCE = new NoopTransactionBridge();

        private NoopTransactionBridge() {
        }

        /**
         * 只有 {@link Mode#READ_ONLY} 能走到这里（且调用方已接受「无事务只读」的风险）。
         *
         * @param mode 事务语义
         * @param work 实际工作
         * @param <T>  返回值类型
         * @return 工作单元的返回值
         * @throws IllegalStateException 当 mode 为 {@code ROLLBACK} 或 {@code COMMIT}
         */
        @Override
        public <T> T inTransaction(Mode mode, Supplier<T> work) {
            if (mode != Mode.READ_ONLY) {
                throw new IllegalStateException(
                        "NoopTransactionBridge 无法保证 " + mode + "：当前环境没有事务管理器。"
                                + "调用方应先检查 isSupported() 并拒绝该请求。");
            }
            return work.get();
        }

        /** @return 恒为 {@code false} */
        @Override
        public boolean isSupported() {
            return false;
        }

        /** @return 固定文案 {@code none (no transaction protection)} */
        @Override
        public String description() {
            return "none (no transaction protection)";
        }
    }
}
