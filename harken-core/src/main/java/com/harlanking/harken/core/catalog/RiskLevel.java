package com.harlanking.harken.core.catalog;

import java.util.Locale;

/**
 * 预置脚本的危险等级。受限模式用它来决定「这个脚本能不能出现在列表里」。
 *
 * <p>三档的取舍：
 * <ul>
 *   <li>{@link #READ} —— 只查询，不改任何状态。受限模式默认可见。</li>
 *   <li>{@link #WRITE} —— 改数据，但受事务回滚 + 行数闸保护，回滚即不落库。</li>
 *   <li>{@link #DANGEROUS} —— DDL、进程级操作等结构性动作。受限模式默认不出现，
 *       必须显式把 {@code max-risk-for-restricted} 调到 {@code DANGEROUS} 才放行。</li>
 * </ul>
 *
 * <p>级别之间的大小用 {@link #ordinal()} 比较 —— {@code READ < WRITE < DANGEROUS}，
 * 这个顺序就是定义顺序，不要改。
 *
 * @author harlan
 * @since 1.0.0
 */
public enum RiskLevel {

    /** 只读。 */
    READ,
    /** 写（受事务保护）。 */
    WRITE,
    /** 高危：结构性/进程级动作，受限模式默认屏蔽。 */
    DANGEROUS;

    /**
     * 把配置或 JSON 里的字符串解析成枚举。
     *
     * @param raw 例如 {@code "write"} / {@code "WRITE"}；{@code null} 或空当作 {@link #READ}
     * @return 枚举；无法识别时按 {@link #READ} 处理（最保守且不影响加载）
     */
    public static RiskLevel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return READ;
        }
        try {
            return RiskLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return READ;
        }
    }
}
