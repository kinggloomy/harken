package com.harlanking.harken.core.impl;

import com.harlanking.harken.core.spi.BeanResolver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Map 支撑的 {@link BeanResolver}。给非 Spring 场景用，也给单元测试用。
 *
 * <p>构造时复制一份 Map，所以调用方后续修改原 Map 不会影响这里 —— 执行期间
 * 解析结果必须稳定，否则同一段脚本两次执行可能拿到不同对象。
 *
 * <p>{@link #typeOf(String)} 返回实例的<b>运行时类型</b>（{@code getClass()}）。
 * 对补全来说这已经够用；需要接口类型时请用 Spring 实现。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class MapBeanResolver implements BeanResolver {

    private final Map<String, Object> beans;

    /**
     * @param beans 名字到实例的映射，{@code null} 视为空；内容会被复制
     */
    public MapBeanResolver(Map<String, Object> beans) {
        this.beans = beans == null ? Map.of() : new LinkedHashMap<>(beans);
    }

    /** {@inheritDoc} */
    @Override
    public boolean contains(String name) {
        return beans.containsKey(name);
    }

    /** {@inheritDoc} */
    @Override
    public Object get(String name) {
        return beans.get(name);
    }

    /** {@inheritDoc} */
    @Override
    public Class<?> typeOf(String name) {
        Object value = beans.get(name);
        return value == null ? null : value.getClass();
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> names() {
        return beans.keySet();
    }
}
