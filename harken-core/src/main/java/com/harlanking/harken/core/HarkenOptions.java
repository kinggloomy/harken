package com.harlanking.harken.core;

/**
 * 一次执行的参数集合。不可变 record，避免执行过程中被外部改动。
 *
 * <p>刻意<b>没有</b> {@code aiReadOnly} 这类开关：AI 通道的只读是请求类型带来的
 * 编译期属性，不是运行时配置（见 {@link com.harlanking.harken.core.api.ReadOnlyRequest}）。
 * 一个可以关掉的只读，等于假只读。
 *
 * <p>构造后用 {@code withXxx} 派生新实例，不要用 setter 风格改字段。
 *
 * @param timeoutMs             单次执行的硬超时（毫秒）。超时后线程被中断；不可中断的阻塞调用只能丢弃，
 *                              这是尽力而为的语义，不是保证
 * @param maxScriptLength       脚本源码字符数上限，超限直接拒绝。防止有人粘贴一整个文件进来
 * @param resultMaxBytes        {@code return} 值的 UTF-8 字节上限，超出按字符边界截断并置
 *                              {@code truncated=true}
 * @param outputMaxBytes        {@code println} 输出的 UTF-8 字节上限，同上
 * @param requireTransactionForAi 无事务保护时是否拒绝执行 AI 通道的只读脚本。默认 {@code true}，
 *                              因为「只读」在 bean 方法内部可能包含写操作，没有回滚就兜不住
 * @author harlan
 * @since 1.0.0
 */
public record HarkenOptions(
        long timeoutMs,
        int maxScriptLength,
        int resultMaxBytes,
        int outputMaxBytes,
        boolean requireTransactionForAi
) {

    /** 生产可用的默认值：10 秒超时、100KB 脚本、8KB 返回值、32KB 输出。 */
    public static HarkenOptions defaults() {
        return new HarkenOptions(10_000, 102_400, 8_192, 32_768, true);
    }

    /**
     * 派生一个新实例，只替换超时时间。
     *
     * @param v 超时毫秒数，必须为正数
     * @return 新的参数对象
     */
    public HarkenOptions withTimeoutMs(long v) {
        return new HarkenOptions(v, maxScriptLength, resultMaxBytes, outputMaxBytes, requireTransactionForAi);
    }

    /**
     * 派生一个新实例，只替换脚本长度上限。
     *
     * @param v 字符数上限
     * @return 新的参数对象
     */
    public HarkenOptions withMaxScriptLength(int v) {
        return new HarkenOptions(timeoutMs, v, resultMaxBytes, outputMaxBytes, requireTransactionForAi);
    }

    /**
     * 派生一个新实例，只替换返回值字节上限。
     *
     * @param v UTF-8 字节上限
     * @return 新的参数对象
     */
    public HarkenOptions withResultMaxBytes(int v) {
        return new HarkenOptions(timeoutMs, maxScriptLength, v, outputMaxBytes, requireTransactionForAi);
    }

    /**
     * 派生一个新实例，只替换输出字节上限。
     *
     * @param v UTF-8 字节上限
     * @return 新的参数对象
     */
    public HarkenOptions withOutputMaxBytes(int v) {
        return new HarkenOptions(timeoutMs, maxScriptLength, resultMaxBytes, v, requireTransactionForAi);
    }

    /**
     * 派生一个新实例，只替换「无事务时是否拒绝 AI 只读」。
     *
     * @param v {@code true} 表示无事务保护时拒绝执行 AI 通道脚本
     * @return 新的参数对象
     */
    public HarkenOptions withRequireTransactionForAi(boolean v) {
        return new HarkenOptions(timeoutMs, maxScriptLength, resultMaxBytes, outputMaxBytes, v);
    }
}
