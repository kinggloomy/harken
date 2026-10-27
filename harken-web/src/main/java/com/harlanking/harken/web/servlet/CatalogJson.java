package com.harlanking.harken.web.servlet;

import com.harlanking.harken.core.catalog.ParamDef;
import com.harlanking.harken.core.catalog.ScriptDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把脚本目录序列化成给前端的 JSON。
 *
 * <p>关键取舍：受限模式下<b>不下发脚本源码</b>（{@code includeScript=false}），
 * 否则用户把源码复制下来就等于绕过了「不能写代码」的限制。
 * FULL 模式把源码一并下发，供「模板插入编辑器」用。
 *
 * <p>所有字段都做「空值不输出」处理：目录里有的脚本没写 {@code defaultValue} /
 * {@code description} 等可选字段，Jackson 之外我们手写的序列化不能因为 {@code null}
 * 就整条炸掉（{@code Map.copyOf} 遇 null 直接 NPE）。前端拿到缺字段比拿到 null 更省心。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class CatalogJson {

    private CatalogJson() {
    }

    /**
     * 序列化整个列表。
     *
     * @param defs          脚本定义
     * @param includeScript 是否包含脚本源码
     * @return 每个定义转成一个 Map
     */
    public static List<Map<String, Object>> toList(List<ScriptDefinition> defs, boolean includeScript) {
        if (defs == null || defs.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(defs.size());
        for (ScriptDefinition d : defs) {
            out.add(toMap(d, includeScript));
        }
        return out;
    }

    /**
     * 序列化单条。
     *
     * @param d            脚本定义
     * @param includeScript 是否包含脚本源码
     * @return Map（不含 null 值的字段）
     */
    public static Map<String, Object> toMap(ScriptDefinition d, boolean includeScript) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", d.id());
        m.put("name", d.name());
        putIfPresent(m, "description", d.description());
        m.put("risk", d.risk().name());
        m.put("commitAllowed", d.commitAllowed());
        m.put("params", paramsToMap(d.params()));
        if (includeScript) {
            putIfPresent(m, "script", d.script());
        }
        return m;
    }

    private static List<Map<String, Object>> paramsToMap(List<ParamDef> params) {
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(params.size());
        for (ParamDef p : params) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", p.name());
            m.put("type", p.type());
            m.put("required", p.required());
            putIfPresent(m, "label", p.label());
            putIfPresent(m, "defaultValue", p.defaultValue());
            putIfPresent(m, "description", p.description());
            out.add(m);
        }
        return out;
    }

    private static void putIfPresent(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }
}
