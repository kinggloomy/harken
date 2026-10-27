package com.harlanking.harken.core.spi;

import java.util.Map;
import java.util.Set;

/**
 * Bean 解析器：脚本里出现未声明变量时，靠它去容器里找。
 *
 * <p>core 只定义接口，不依赖 Spring。Spring 模块提供 {@code SpringBeanResolver}，
 * 非 Spring 项目可以直接用 {@link #of(Map)} 包装一个 Map。
 *
 * <p><b>实现必须懒解析：</b> {@link #get(String)} 只在脚本真正用到某个名字时才被调用。
 * 绝不能在启动时把所有 Bean 都 {@code getBean()} 一遍——那会提前触发
 * {@code @PostConstruct}、破坏 {@code @Lazy}、可能踩到循环依赖，并让启动时间
 * 从 3 秒变成 30 秒。
 *
 * <p><b>{@link #contains(String)} 与 {@link #typeOf(String)} 不能有实例化副作用</b>，
 * 它们会被补全功能与元数据扫描高频调用。
 *
 * @author harlan
 * @since 1.0.0
 */
public interface BeanResolver {

    /**
     * 这个名字是否对应一个可解析的 Bean。补全与提示用，<b>不能有副作用</b>。
     *
     * @param name 变量名
     * @return 存在返回 {@code true}
     */
    boolean contains(String name);

    /**
     * 真正取实例。只有脚本用到时才调。
     *
     * @param name 变量名
     * @return Bean 实例；不存在返回 {@code null}（不要抛异常，避免把「名字拼错」
     *         变成「容器故障」）
     */
    Object get(String name);

    /**
     * 类型，用于补全元数据。<b>不能触发实例化。</b>
     *
     * @param name 变量名
     * @return Bean 的声明类型；未知返回 {@code null}
     */
    Class<?> typeOf(String name);

    /**
     * 全部可用名字，用于补全。
     *
     * @return 名字集合，不能返回 {@code null}
     */
    Set<String> names();

    /**
     * 用 Map 构造一个解析器。
     *
     * @param map 名字到实例的映射
     * @return 解析器
     */
    static BeanResolver of(Map<String, Object> map) {
        return new com.harlanking.harken.core.impl.MapBeanResolver(map);
    }

    /**
     * 空实现：非 Spring、无容器场景。
     *
     * @return 永远找不到任何名字的解析器
     */
    static BeanResolver empty() {
        return new BeanResolver() {
            @Override public boolean contains(String name) { return false; }
            @Override public Object get(String name) { return null; }
            @Override public Class<?> typeOf(String name) { return null; }
            @Override public Set<String> names() { return Set.of(); }
        };
    }
}
