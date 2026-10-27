package com.harlanking.harken.core.spi;

/**
 * 写入守卫。core 只知道「一次执行开始 / 结束 / 影响了多少行」，
 * 不知道 JDBC、不知道 SQL、不知道表名 —— 那些都在 {@code harken-jdbc} 里。
 *
 * <h2>它解决什么问题</h2>
 *
 * <p>事务能保证「不提交」，但保证不了「不执行」。一条
 * {@code update demo_order set status='PAID'} 少写了 where，事务会老老实实
 * 回滚它 —— 看起来没事，可等用户勾上提交，八万行就一起变了。
 * 行数闸的意义是：<b>在执行过程中就知道超了</b>，当场抛异常让事务回滚，
 * 而不是等提交之后靠审计日志复盘。
 *
 * <h2>为什么要做成 SPI，而不是写进 core</h2>
 *
 * <p>core 的红线是不依赖任何框架、也不做 SQL 解析。守卫必然要认识 JDBC
 * （{@code DataSource} / {@code Statement} 代理），如果塞进 core，
 * 那个「26 个类零依赖」的承诺就变成一句口号。所以这里只留一个极窄的协议：
 *
 * <pre>
 * begin(traceId) ──▶ 脚本在该线程执行期间，任何写入都会被累加与检查
 * 返回的 Scope   ──▶ close() 时清除线程上下文，affectedRows() 给出总行数
 * </pre>
 *
 * <h2>线程模型的硬要求</h2>
 *
 * <p>{@link #begin(String)} 必须在<b>执行脚本的那个线程</b>上调用，因为实现普遍用
 * {@code ThreadLocal} 传递当前计数。{@code HarkenService} 已经这么做了 ——
 * 它在 worker 线程内部调用，不在提交任务的外层。
 *
 * <h2>它不是什么</h2>
 *
 * <p><b>不是安全边界。</b> 守卫通过 SQL 文本做保守判断，能被
 * {@code CallableStatement} 里的存储过程、被视图、被动态拼 SQL 绕开。
 * 它防的是手滑，不是防入侵 —— 这一条写进 README 与 SECURITY.md。
 *
 * @author harlan
 * @since 1.0.0
 * @see TransactionBridge
 */
public interface WriteGuard {

    /**
     * 开始一次执行，返回本次执行的计数作用域。
     *
     * <p>实现必须允许嵌套调用（保存并恢复上一层状态），因为同一线程上
     * 可能发生「脚本里又调了一次 Harken」（少见但合法）。
     *
     * @param traceId 本次执行的追踪 ID，用于错误信息，可为 {@code null}
     * @return 作用域，调用方必须用 try-with-resources 关闭
     */
    Scope begin(String traceId);

    /**
     * 面向启动日志与 UI 状态条的可读描述。
     *
     * @return 例如 {@code rows<=1000, deny=[supply_order]}
     */
    default String description() {
        return getClass().getSimpleName();
    }

    /**
     * 一次执行的作用域。
     *
     * @author harlan
     * @since 1.0.0
     */
    interface Scope extends AutoCloseable {

        /**
         * 本次执行累计影响的行数。
         *
         * <p>没有测量能力时返回 {@code -1}，<b>不要用 0 冒充</b>：
         * 「没测」和「测了，是 0」在审计里含义完全不同。
         *
         * @return 行数，或 {@code -1} 表示未测量
         */
        long affectedRows();

        /**
         * 清除线程上下文。实现必须幂等，且不抛异常。
         */
        @Override
        void close();

        /** 什么都不做的空作用域。 */
        Scope NONE = new Scope() {
            /** @return 恒为 {@code -1}：未测量 */
            @Override
            public long affectedRows() {
                return -1L;
            }

            /** 无操作。 */
            @Override
            public void close() {
                // 无状态
            }
        };
    }

    /**
     * 没有守卫时的默认实现：不做任何检查，也不报告行数。
     *
     * <p>它<strong>不</strong>假装测量过 —— {@link Scope#affectedRows()} 返回
     * {@code -1}，于是审计里这一列是「未知」而不是「0 行」。
     */
    WriteGuard NONE = new WriteGuard() {

        /**
         * @param traceId 追踪 ID，未使用
         * @return 空作用域
         */
        @Override
        public Scope begin(String traceId) {
            return Scope.NONE;
        }

        /** @return 固定文案，提示没有行数保护 */
        @Override
        public String description() {
            return "none (no affected-row limit)";
        }
    };
}
