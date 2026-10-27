package com.harlanking.harken.core.engine;

import com.harlanking.harken.core.spi.BeanResolver;

import java.util.Map;

/**
 * 一次执行的输入环境：预置变量 + Bean 解析器。
 *
 * <p>变量分两类，优先级不同：
 * <ol>
 *   <li>{@code variables} —— {@link com.harlanking.harken.core.spi.BindingContributor}
 *       注入的（{@code currentUser} / {@code traceId} / {@code now} ...），显式存入 Binding，命中快</li>
 *   <li>{@code beanResolver} —— 兜底，脚本里出现未声明变量时才去问容器</li>
 * </ol>
 * 这个优先级很重要：显式注入的变量名可以覆盖同名 Bean，而不会被 Bean 抢走。
 * 反过来想，如果 Bean 优先，那么用户想注入一个叫 {@code dataSource} 的变量就永远失败。
 *
 * <p>不可变对象，可安全跨线程传递。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class ExecutionContext {

    private final Map<String, Object> variables;
    private final BeanResolver beanResolver;

    /**
     * @param variables    预置变量，{@code null} 视为空集合。注意会被<b>复制</b>到 Binding，
     *                     所以可以安全传入 {@code Map.of()}
     * @param beanResolver Bean 解析器，{@code null} 视为空解析器
     */
    public ExecutionContext(Map<String, Object> variables, BeanResolver beanResolver) {
        this.variables = variables == null ? Map.of() : variables;
        this.beanResolver = beanResolver == null ? BeanResolver.empty() : beanResolver;
    }

    /**
     * @return 预置变量，只读视图
     */
    public Map<String, Object> variables() {
        return variables;
    }

    /**
     * @return Bean 解析器，永不返回 {@code null}
     */
    public BeanResolver beanResolver() {
        return beanResolver;
    }
}
