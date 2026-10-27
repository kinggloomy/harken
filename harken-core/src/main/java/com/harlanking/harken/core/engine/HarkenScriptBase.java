package com.harlanking.harken.core.engine;

import groovy.lang.Script;
import org.codehaus.groovy.runtime.InvokerHelper;

import java.io.PrintWriter;

/**
 * 脚本基类。所有 Harken 脚本编译后都继承它，而不是 {@code groovy.lang.Script}。
 *
 * <h2>为什么必须自定义基类</h2>
 * Groovy 4 的 {@code groovy.lang.Script#print()} 直接写 {@code System.out}，
 * 而且<b>没有 {@code setOut} 方法</b>（Groovy 3 有，4 移除了）。想捕获 {@code println}
 * 输出只有三条路：
 *
 * <ol>
 *   <li>{@code System.setOut()} —— 全局副作用，两个脚本并发执行会串台。否决。</li>
 *   <li>让用户写 {@code out.println()} —— 改变脚本写法，抄来的代码跑不通。否决。</li>
 *   <li><b>自定义 Script 基类，重写 print/println</b> —— 通过
 *       {@code CompilerConfiguration.setScriptBaseClass()} 让编译器把脚本编译成这个类的子类。
 *       脚本里写 {@code println "x"} 编译成 {@code this.println("x")}，虚方法分派到这里的实现。
 *       <b>这就是当前方案。</b></li>
 * </ol>
 *
 * <h2>已知限制</h2>
 * <p>{@code System.out.println(...)} 这种显式写法仍然漏网——它是直接调静态字段，
 * 不经过基类，输出会进宿主应用的控制台。这一点写在 {@code SECURITY.md} 的「已知限制」里。
 *
 * <p>本类只作为编译期的基类存在，业务代码不需要直接使用它。
 *
 * @author harlan
 * @since 1.0.0
 */
public abstract class HarkenScriptBase extends Script {

    /** 默认丢弃而不是写 {@code System.out}，避免忘记注入时污染宿主应用的控制台。 */
    private PrintWriter harkenOut = new PrintWriter(java.io.Writer.nullWriter());

    /**
     * 注入本次执行的输出目标。由 {@link GroovyScriptEngine#run} 在每次执行前调用。
     *
     * @param out 输出目标；{@code null} 会被替换成丢弃型 Writer
     */
    public void setHarkenOut(PrintWriter out) {
        this.harkenOut = out != null ? out : new PrintWriter(java.io.Writer.nullWriter());
    }

    /**
     * @return 当前输出目标，永不为 {@code null}
     */
    public PrintWriter getHarkenOut() {
        return harkenOut;
    }

    /** {@inheritDoc} */
    @Override
    public void print(Object value) {
        harkenOut.print(InvokerHelper.toString(value));
    }

    /** {@inheritDoc} */
    @Override
    public void println(Object value) {
        harkenOut.println(InvokerHelper.toString(value));
    }

    /** {@inheritDoc} */
    @Override
    public void println() {
        harkenOut.println();
    }

    /** {@inheritDoc} */
    @Override
    public void printf(String format, Object value) {
        harkenOut.printf(format, value);
    }

    /** {@inheritDoc} */
    @Override
    public void printf(String format, Object[] values) {
        harkenOut.printf(format, values);
    }
}
