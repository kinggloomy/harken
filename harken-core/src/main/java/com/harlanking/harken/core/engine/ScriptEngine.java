package com.harlanking.harken.core.engine;

import java.io.PrintWriter;

/**
 * 脚本引擎抽象。当前只有 Groovy 实现，接口留着是为了将来能换（Kotlin Script / JShell）。
 *
 * <p>刻意不暴露 {@code groovy.lang.Binding}、{@code Script} 这些类型：
 * core 的公开 API 里出现 Groovy 类型，等于把 Groovy 变成强制依赖，
 * 也就等于放弃了「任意项目都能嵌入」这个目标。
 *
 * @author harlan
 * @since 1.0.0
 */
public interface ScriptEngine {

    /**
     * 只编译不执行，用于前端「干跑」画语法红线。
     *
     * <p>语义契约：这个方法有副作用——编译产物会进缓存，下一次 {@link #run} 直接命中。
     * 所以「先干跑再执行」不会编译两次。
     *
     * @param script 待编译的源码
     * @throws com.harlanking.harken.core.HarkenException.Compilation 编译失败，带行列号
     */
    void check(String script);

    /**
     * 编译（命中缓存则跳过）并执行。
     *
     * @param context 本次执行的变量与 Bean 解析器，不能为 {@code null}
     * @param script  脚本源码
     * @param out     脚本里 {@code println} 的去处。传 per-run 的 PrintWriter，
     *                绝不能碰 {@code System.setOut}——那是全局副作用，并发会串台
     * @return 脚本 {@code return} 的值，没有 return 则为 {@code null}
     * @throws com.harlanking.harken.core.HarkenException.Compilation 编译失败
     * @throws com.harlanking.harken.core.HarkenException              实例化或执行失败
     */
    Object run(ExecutionContext context, String script, PrintWriter out);
}
