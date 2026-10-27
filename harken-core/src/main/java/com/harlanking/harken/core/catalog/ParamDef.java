package com.harlanking.harken.core.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 预置脚本的一个参数定义。脚本运行时通过 {@code params.名字} 拿到值。
 *
 * <p>类型一共四种，故意很少：{@code string} / {@code number} / {@code boolean} / {@code date}。
 * 复杂结构交给脚本自己拼 —— 受限模式的用户不会写 Groovy，给他们太多类型反而是负担。
 *
 * @param name         参数名，对应脚本里的 {@code params.name}；必填
 * @param type         string | number | boolean | date，缺省 string
 * @param required     是否必填；必填项缺失时请求会被拒绝，不会进入执行
 * @param label        界面显示名，缺省等于 name
 * @param defaultValue 默认值（字符串形式，运行时按 type 转）
 * @param description  说明，可为空
 * @author harlan
 * @since 1.0.0
 */
public record ParamDef(
        String name,
        String type,
        boolean required,
        String label,
        String defaultValue,
        String description
) {

    /** 已知类型集合。 */
    public static final List<String> KNOWN_TYPES = List.of("string", "number", "boolean", "date");

    /**
     * @throws NullPointerException 当 name 为 {@code null}
     */
    public ParamDef {
        Objects.requireNonNull(name, "param name");
        if (type == null || type.isBlank()) {
            type = "string";
        } else {
            type = type.trim().toLowerCase(Locale.ROOT);
        }
        if (label == null || label.isBlank()) {
            label = name;
        }
    }

    /**
     * 把 UI 传来的原始值（JSON 解码后的字符串/数字/布尔）转成本类型的值。
     *
     * <p>校验失败抛 {@link IllegalArgumentException} —— 调用方据此返回 400，
     * 不把脏数据喂进脚本。
     *
     * @param raw UI 传来的原始值，可能为 {@code null}
     * @return 转换后的值；{@code string} 返回 {@link String}，{@code number} 返回
     *         {@link Number}，{@code boolean} 返回 {@link Boolean}，{@code date} 返回 ISO 日期字符串
     * @throws IllegalArgumentException 类型不匹配或空字符串当数字/布尔
     */
    public Object coerce(Object raw) {
        if (raw == null) {
            if (required) {
                throw new IllegalArgumentException("参数 " + name + " 为必填项");
            }
            return defaultValue == null ? null : defaultValue;
        }
        return switch (type) {
            case "number" -> toNumber(raw);
            case "boolean" -> toBoolean(raw);
            case "date" -> String.valueOf(raw).trim();
            default -> String.valueOf(raw);
        };
    }

    private Number toNumber(Object raw) {
        if (raw instanceof Number n) {
            return n;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("参数 " + name + " 需要数字，收到空值");
        }
        try {
            // 优先 long，带小数点再 double，覆盖绝大多数业务主键/金额场景
            return text.contains(".") || text.contains("e") || text.contains("E")
                    ? Double.parseDouble(text) : Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + name + " 需要数字，收到 " + text);
        }
    }

    private Boolean toBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("参数 " + name + " 需要布尔值，收到 " + text);
    }
}
