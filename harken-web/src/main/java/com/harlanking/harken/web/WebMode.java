package com.harlanking.harken.web;

import java.util.Locale;

/**
 * 控制台模式。
 *
 * <p>{@link #FULL} —— 用户可自由编写脚本（开发 / 运维用）。
 * {@link #RESTRICTED} —— 用户只能从预置目录选脚本 + 填参数执行（非开发用户用），
 * 编辑器和脚本源码都不下发。
 *
 * <p>这是「能不能写代码」的分水岭：受限模式把执行入口从「一段文本」锁成「一个菜单项 id」，
 * 用户没有任何办法把任意 Groovy 喂进来。
 *
 * @author harlan
 * @since 1.0.0
 */
public enum WebMode {

    /** 自由编写。 */
    FULL,
    /** 受限：只有预置目录。 */
    RESTRICTED;

    /**
     * 解析配置字符串。
     *
     * @param raw 例如 {@code "restricted"} / {@code "RESTRICTED"}；{@code null} 或空当作 {@link #FULL}
     * @return 枚举；无法识别时按 {@link #FULL} 处理（最宽松、最不影响现有部署）
     */
    public static WebMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FULL;
        }
        try {
            return WebMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FULL;
        }
    }
}
