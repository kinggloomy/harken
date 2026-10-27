package com.harlanking.harken.core.spi;

import java.util.Map;

/**
 * 往脚本上下文注入变量。最常用的扩展点。
 *
 * <p>用法：实现类返回 {@code Map.of("currentUser", ..., "traceId", ...)}，
 * 脚本里就能直接用 {@code currentUser}。
 *
 * <p><b>硬要求：实现必须无状态、线程安全。</b> 扩展点是单例，脚本执行在多线程，
 * 同一个 {@link #contribute(Context)} 会被并发调用。在字段里累计计数是典型错误：
 * <pre>{@code
 * // 错误：并发下会丢数据，而且 count 的含义在多个使用者之间共享
 * private int count = 0;
 * public Map<String, Object> contribute(Context c) { count++; ... }
 * }</pre>
 *
 * <p><b>抛异常会中断执行。</b> 变量没准备好，脚本跑下去也是错，不如直接失败。
 *
 * @author harlan
 * @since 1.0.0
 */
public interface BindingContributor {

    /**
     * 提供本次执行要注入的变量。
     *
     * @param context 执行上下文，只读
     * @return 变量映射；返回 {@code null} 或空 Map 表示不注入
     */
    Map<String, Object> contribute(Context context);

    /**
     * 排序值。数字小的先执行，后被同名的覆盖。
     *
     * @return 顺序值，默认 {@code 0}
     */
    default int getOrder() {
        return 0;
    }

    /**
     * 执行上下文。只读，不要在这里做副作用。
     *
     * @param script   脚本源码，可用于按脚本内容决定注入什么
     * @param channel  {@code ai} 或 {@code human}
     * @param operator 操作人，可能为 {@code null}
     * @author harlan
     * @since 1.0.0
     */
    record Context(String script, String channel, String operator) {
    }
}
