package com.harlanking.harken.core.catalog;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一条预置脚本（菜单项）。受限模式下，用户不能写代码，只能从这类菜单项里选一个、
 * 填参数、执行。
 *
 * @param id             唯一 id，前端用它来请求执行（{@code scriptId}）
 * @param name           显示名
 * @param description    说明，可为空
 * @param script         真正的 Groovy 源码；受限模式下<b>不下发给浏览器</b>（防被复制改写）
 * @param risk           危险等级，决定受限模式下是否可见
 * @param commitAllowed  该脚本是否允许提交事务；{@code false} 时受限模式的提交按钮不出现
 * @param params         参数定义；可为空
 * @author harlan
 * @since 1.0.0
 */
public record ScriptDefinition(
        String id,
        String name,
        String description,
        String script,
        RiskLevel risk,
        boolean commitAllowed,
        List<ParamDef> params
) {

    /**
     * @throws NullPointerException 当 id / script 为 {@code null}
     */
    public ScriptDefinition {
        Objects.requireNonNull(id, "script id");
        Objects.requireNonNull(script, "script body");
        if (risk == null) {
            risk = RiskLevel.READ;
        }
        if (params == null) {
            params = List.of();
        }
    }

    /**
     * 校验调用方（UI）传来的参数是否齐全。
     *
     * @param provided UI 传来的 {@code params} 对象（可能缺字段）
     * @return 缺失的必填参数名；空列表表示齐全
     */
    public List<String> missingRequired(Map<String, Object> provided) {
        List<String> missing = new java.util.ArrayList<>();
        Map<String, Object> safe = provided == null ? Map.of() : provided;
        for (ParamDef p : params) {
            if (p.required() && (safe.get(p.name()) == null
                    || (safe.get(p.name()) instanceof String s && s.isBlank()))) {
                missing.add(p.name());
            }
        }
        return List.copyOf(missing);
    }

    /**
     * 按定义把 UI 传来的原始参数转成本类型的值，组装成脚本里可用的 {@code params} Map。
     *
     * <p>缺省参数与默认值逻辑都在这里统一处理：UI 没传、但定义有默认值 → 用默认值；
     * UI 没传、也无默认值、又非必填 → {@code null}。
     *
     * @param provided UI 传来的原始参数
     * @return 转换后的 Map；键是参数名
     * @throws IllegalArgumentException 任何一项类型不匹配或必填项为空
     */
    public Map<String, Object> bind(Map<String, Object> provided) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        Map<String, Object> safe = provided == null ? Map.of() : provided;
        for (ParamDef p : params) {
            Object raw = safe.get(p.name());
            if (raw == null && p.defaultValue() != null) {
                raw = p.defaultValue();
            }
            out.put(p.name(), p.coerce(raw));
        }
        return java.util.Map.copyOf(out);
    }
}
