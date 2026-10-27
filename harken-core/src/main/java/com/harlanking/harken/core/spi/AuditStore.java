package com.harlanking.harken.core.spi;

import java.util.List;

/**
 * 审计存储。默认实现是 {@link com.harlanking.harken.core.impl.Slf4jAuditStore}
 * （零建表，写日志）。可以同时挂多个，例如 {@code memory + slf4j}。
 *
 * <p><b>调用方必须吞掉这里的异常</b>：审计失败不能让执行失败。但必须打 ERROR +
 * 计数指标，因为「审计静默失败」是最危险的状态——你以为有记录，其实没有。
 * 这一条已在 {@code HarkenService#writeAudit} 里实现。
 *
 * <p><b>实现必须线程安全</b>：脚本执行在多线程，{@link #save} 会被并发调用。
 *
 * <p><b>写入必须独立于业务事务</b>：如果放在业务事务里，脚本回滚时审计跟着回滚，
 * 而「被回滚的那次」恰恰最需要审计。JDBC 实现应使用 {@code REQUIRES_NEW}。
 *
 * @author harlan
 * @since 1.0.0
 */
public interface AuditStore {

    /**
     * 持久化一条记录。实现不应抛异常，但调用方仍必须做防御。
     *
     * @param record 审计记录，不能为 {@code null}
     */
    void save(AuditRecord record);

    /**
     * 是否支持结构化查询。写日志的实现返回 {@code false}。
     *
     * @return {@code true} 表示 {@link #query(int)} 会返回真实数据
     */
    default boolean supportsQuery() {
        return false;
    }

    /**
     * 查询最近的记录，按时间倒序。
     *
     * <p>不支持查询的实现<b>返回空列表</b>，不要抛 {@code UnsupportedOperationException}
     * —— 调用方（UI）不该为「审计后端类型不同」写分支。
     *
     * @param limit 最多返回条数
     * @return 记录列表，永不为 {@code null}
     */
    default List<AuditRecord> query(int limit) {
        return List.of();
    }

    /**
     * 用于日志与错误信息的名标识。
     *
     * @return 默认是类简名
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
