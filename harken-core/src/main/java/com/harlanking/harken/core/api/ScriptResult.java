package com.harlanking.harken.core.api;

/**
 * 一次执行的结果。字段刻意做成「给 AI 也能读」的形状。
 *
 * <p>三个设计要点：
 * <ul>
 *   <li>{@code result} 与 {@code output} 分开。返回值是答案，{@code println} 是噪音，
 *       混在一起调用方分不清哪个是结论。</li>
 *   <li>{@code truncated} 与 {@code hint} 必须成对出现。不知道数据被截断，就会拿残缺结果下结论，
 *       这比直接报错更危险。</li>
 *   <li>{@code rolledBack} 显式暴露。让调用方明确知道这次有没有落库。</li>
 * </ul>
 *
 * <h2>为什么 {@code traceId} 与 {@code affectedRows} 在这里</h2>
 * <p>这两项原本只写进了审计。<b>那是错的</b>：控制台的「试跑 → 确认提交」两阶段流程
 * 必须立刻知道「这次影响了几行」，而去审计表里捞「刚刚那条」既慢又可能捞错
 * （并发执行时顺序不保证）。同理，用户看到一条失败结果后想找对应日志，
 * 手里必须已经有 {@code traceId}，否则「能追溯」就成了一句需要先查库才能兑现的话。
 *
 * @param ok           执行是否成功。注意「成功」不等于「已提交」，是否提交看 {@code rolledBack}
 * @param result       脚本 {@code return} 的值经 {@code toString()} 后的文本；未 return 或失败为 {@code null}
 * @param output       脚本 {@code println} 的内容，可能被截断
 * @param error        失败时的错误描述；成功为 {@code null}
 * @param errorType    机器可读的错误类型，例如 {@code COMPILATION}、{@code TIMEOUT}、
 *                     {@code HARKEN_NO_TRANSACTION}、{@code MissingMethodException}
 * @param durationMs   从进入编排到返回的耗时（毫秒）
 * @param rolledBack   本次改动是否确实被事务回滚。{@code true} 表示没有落库；
 *                     {@code false} 有两种情况：真的提交了，或当前环境没有事务、
 *                     谈不上回滚（此时不要把它读成「已提交」）
 * @param truncated    {@code result} 或 {@code output} 是否被截断
 * @param hint         给调用方的补救建议，通常提示「加筛选条件后重试」；未截断为 {@code null}
 * @param channel      通道标识，{@code ai} 或 {@code human}
 * @param traceId      本次执行的追踪 ID，与审计记录、日志里的那个是同一个值
 * @param affectedRows 本次执行累计影响的数据库行数；{@code -1} 表示<b>没有测量</b>
 *                     （没装写入守卫），不要读成「0 行」。被行数闸拦下的那次同样有值：
 *                     「本来会改 200 行」正是最需要看到的数字
 * @author harlan
 * @since 1.0.0
 */
public record ScriptResult(
        boolean ok,
        String result,
        String output,
        String error,
        String errorType,
        long durationMs,
        boolean rolledBack,
        boolean truncated,
        String hint,
        String channel,
        String traceId,
        long affectedRows
) {

    /**
     * 构造一个成功结果，永远不带截断标记。
     *
     * @param result     返回值文本，可为 {@code null}
     * @param output     {@code println} 输出，可为 {@code null}
     * @param durationMs 耗时毫秒
     * @param rolledBack 是否已回滚
     * @param channel    {@code ai} 或 {@code human}
     * @return 成功结果
     */
    public static ScriptResult success(String result, String output, long durationMs,
                                       boolean rolledBack, String channel) {
        return new ScriptResult(true, result, output, null, null, durationMs,
                rolledBack, false, null, channel, null, -1L);
    }

    /**
     * 构造一个失败结果。
     *
     * @param error      错误描述
     * @param errorType  错误类型标识
     * @param output     失败前已经产生的输出
     * @param durationMs 耗时毫秒
     * @param rolledBack 是否已回滚
     * @param channel    {@code ai} 或 {@code human}
     * @return 失败结果
     */
    public static ScriptResult failure(String error, String errorType, String output,
                                       long durationMs, boolean rolledBack, String channel) {
        return new ScriptResult(false, null, output, error, errorType, durationMs,
                rolledBack, false, null, channel, null, -1L);
    }

    /**
     * 派生一个带截断标记的结果；{@code value} 为 {@code false} 时原样返回 {@code this}。
     *
     * @param value    是否截断
     * @param hintText 截断提示
     * @return 新的结果对象，或自身
     */
    public ScriptResult truncatedTo(boolean value, String hintText) {
        if (!value) {
            return this;
        }
        return new ScriptResult(ok, result, output, error, errorType, durationMs,
                rolledBack, true, hintText, channel, traceId, affectedRows);
    }

    /**
     * 派生一个补齐追踪信息的结果。
     *
     * <p>{@code traceId} 与 {@code affectedRows} 只有在编排层才知道，
     * 所以执行结束后由 {@code HarkenService} 补上。放在这里而不是构造参数里，
     * 是为了让「构造结果」的调用方（测试、SPI 实现）不必关心这两个字段的来历。
     *
     * @param newTraceId       追踪 ID
     * @param newAffectedRows  影响行数，{@code -1} 表示未测量
     * @return 新的结果对象
     */
    public ScriptResult withTrace(String newTraceId, long newAffectedRows) {
        return new ScriptResult(ok, result, output, error, errorType, durationMs,
                rolledBack, truncated, hint, channel, newTraceId, newAffectedRows);
    }
}
