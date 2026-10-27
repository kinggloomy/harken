package com.harlanking.harken.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 写入守卫的策略：影响行数上限、关键表黑名单、是否允许 DDL。
 *
 * <p>不可变。构造时把表名统一转小写并去掉空白，因为 MySQL 在 Linux 上表名大小写敏感、
 * 在 Windows 上不敏感，而 {@code supply_order} 与 {@code SUPPLY_ORDER} 在运维心里是同一张表。
 *
 * <h2>默认值的选择</h2>
 *
 * <p>{@link #passThrough()}（不限行数、无黑名单、允许 DDL）是默认值。理由不是「少管闲事」，
 * 而是<b>没配策略时不该改动应用的 JDBC 行为</b>：包装 {@code DataSource} 意味着
 * 所有 SQL 多走一层代理，一旦代理有 bug，受害的是整个应用而不只是这个工具。
 * 因此 starter 只在策略「有内容」时才包装。
 *
 * @param maxAffectedRows 单次执行累计影响行数上限；{@code <= 0} 表示不限
 * @param deniedTables    关键表黑名单，支持精确名、头部通配（{@code *_order}）与尾部通配（{@code tmp_*}）
 * @param denyDdl         是否拒绝 DDL（{@code create / alter / drop / truncate / rename}）
 * @author harlan
 * @since 1.0.0
 */
public record GuardPolicy(long maxAffectedRows, List<String> deniedTables, boolean denyDdl) {

    /**
     * 规范化入参。
     *
     * @throws IllegalArgumentException 当 {@code maxAffectedRows} 小于 {@code -1}
     */
    public GuardPolicy {
        if (maxAffectedRows < -1) {
            throw new IllegalArgumentException("maxAffectedRows 不能小于 -1：" + maxAffectedRows);
        }
        deniedTables = normalize(deniedTables);
    }

    /**
     * 什么都不限制的策略。装了守卫也不会有任何拦截行为。
     *
     * @return 直通策略
     */
    public static GuardPolicy passThrough() {
        return new GuardPolicy(0L, List.of(), false);
    }

    /**
     * 是否启用行数上限。
     *
     * @return {@code true} 表示超过 {@link #maxAffectedRows()} 会抛异常并回滚
     */
    public boolean rowLimitEnabled() {
        return maxAffectedRows > 0;
    }

    /**
     * 策略是否「什么都没配」。starter 据此决定要不要包装 {@code DataSource}。
     *
     * @return {@code true} 表示三项限制全都没配
     */
    public boolean nothingToEnforce() {
        return !rowLimitEnabled() && deniedTables.isEmpty() && !denyDdl;
    }

    /**
     * 判断一个标识符是否命中黑名单。
     *
     * <p>支持三种写法：精确名 {@code supply_order}、头部通配 {@code *_order}
     * （配到所有以 {@code _order} 结尾的表）、尾部通配 {@code tmp_*}。
     * 单独的 {@code *} 表示拦一切表。
     *
     * @param identifier SQL 里出现的一个标识符（已转小写、已去掉引号）
     * @return {@code true} 表示命中
     */
    public boolean denies(String identifier) {
        if (identifier == null || identifier.isEmpty() || deniedTables.isEmpty()) {
            return false;
        }
        String id = identifier.toLowerCase(Locale.ROOT);
        for (String pattern : deniedTables) {
            boolean head = pattern.startsWith("*");
            boolean tail = pattern.endsWith("*");
            if (head && tail && pattern.length() > 2) {
                if (id.contains(pattern.substring(1, pattern.length() - 1))) {
                    return true;
                }
            } else if (head) {
                // "*_order" → 后缀匹配，这是运维最常用的写法
                if (id.endsWith(pattern.substring(1))) {
                    return true;
                }
            } else if (tail) {
                if (id.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            } else if (id.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 面向启动日志的单行描述。
     *
     * @return 例如 {@code rows<=1000, deny=[supply_order], ddl=deny}
     */
    public String describe() {
        if (nothingToEnforce()) {
            return "none (pass-through)";
        }
        List<String> parts = new ArrayList<>(3);
        if (rowLimitEnabled()) {
            parts.add("rows<=" + maxAffectedRows);
        }
        if (!deniedTables.isEmpty()) {
            parts.add("deny=" + deniedTables);
        }
        if (denyDdl) {
            parts.add("ddl=deny");
        }
        return String.join(", ", parts);
    }

    private static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String v = item.trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty()) {
                continue;
            }
            // 去掉反引号/双引号：配置里写 `supply_order` 和使用者手写 SQL 时的形式要一致
            v = v.replace("`", "").replace("\"", "");
            if (!out.contains(v)) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }
}
