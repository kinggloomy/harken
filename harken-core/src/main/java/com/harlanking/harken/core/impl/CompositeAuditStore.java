package com.harlanking.harken.core.impl;

import com.harlanking.harken.core.spi.AuditRecord;
import com.harlanking.harken.core.spi.AuditStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 把多条审计存储当成一条用。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>生产上常见的组合是三个一起挂：日志（给运维看、可长期留存）、数据库
 * （给合规查、可 join 业务表）、内存（给控制台的「最近执行」面板，零延迟）。
 * 而 {@link AuditStore} 是单个注入点 —— 非 Spring 场景里
 * {@code HarkenRuntime.builder().auditStore(...)} 只能传一个对象。
 *
 * <h2>失败隔离</h2>
 *
 * <p>某一条通道写失败<b>不能影响其他通道</b>：数据库审计表被 DBA 锁了，
 * 不该连带让日志也没写下来。所以每个子存储单独 try/catch，失败打 ERROR 并继续。
 *
 * <p>这一点和 {@code HarkenService} 的做法重复，但两处都需要：服务层兜的是
 * 「存储数组」，本类兜的是「被当成单个存储注入进来的组合」。
 *
 * <h2>查询只取一个</h2>
 *
 * <p>{@link #query(int)} 取<b>第一个</b>支持查询的子存储，不做合并。
 * 合并是错的：三个后端的记录集合可能完全不同（内存只留 200 条、库里有全量），
 * 拼一起会得到重复又缺漏的结果，还不如老老实实返回一个来源。
 * 想让 UI 查数据库，就把 {@code JdbcAuditStore} 放在前面。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class CompositeAuditStore implements AuditStore {

    private static final Logger log = LoggerFactory.getLogger(CompositeAuditStore.class);

    private final List<AuditStore> delegates;

    /**
     * 组合多个存储。
     *
     * @param stores 子存储，{@code null} 元素会被忽略
     * @throws IllegalArgumentException 当过滤后一个都不剩（那样等于没有审计，却看起来有）
     */
    public CompositeAuditStore(AuditStore... stores) {
        // 用 Arrays.asList 而不是 List.of：后者遇到 null 元素会抛 NPE，
        // 而这里的约定是「null 元素忽略」，不该让一个手滑的 null 变成启动失败
        this(Arrays.asList(stores == null ? new AuditStore[0] : stores));
    }

    /**
     * 组合多个存储。
     *
     * @param stores 子存储列表，{@code null} 元素会被忽略
     * @throws IllegalArgumentException 当过滤后一个都不剩
     */
    public CompositeAuditStore(List<AuditStore> stores) {
        List<AuditStore> valid = new ArrayList<>();
        if (stores != null) {
            for (AuditStore store : stores) {
                if (store != null) {
                    valid.add(store);
                }
            }
        }
        if (valid.isEmpty()) {
            throw new IllegalArgumentException(
                    "CompositeAuditStore 至少需要一个子存储；空组合等于审计静默消失");
        }
        this.delegates = List.copyOf(valid);
    }

    /**
     * 逐个子存储写一遍，单个失败不影响其他。
     *
     * @param record 审计记录
     */
    @Override
    public void save(AuditRecord record) {
        for (AuditStore delegate : delegates) {
            try {
                delegate.save(record);
            } catch (Throwable t) {
                // 一条通道坏了，不能连带其他通道一起哑掉
                log.error("Harken 审计通道写入失败：{}（trace={}）",
                        delegate.name(), record == null ? null : record.traceId(), t);
            }
        }
    }

    /**
     * @return 任一子存储支持查询即为 {@code true}
     */
    @Override
    public boolean supportsQuery() {
        for (AuditStore delegate : delegates) {
            if (delegate.supportsQuery()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从第一个支持查询的子存储取数据。不合并、不去重 —— 理由见类注释。
     *
     * @param limit 最多返回条数
     * @return 记录列表；没有可查询的子存储时返回空列表
     */
    @Override
    public List<AuditRecord> query(int limit) {
        for (AuditStore delegate : delegates) {
            if (!delegate.supportsQuery()) {
                continue;
            }
            try {
                return delegate.query(limit);
            } catch (RuntimeException e) {
                log.warn("Harken 审计查询失败，尝试下一个通道：{}", delegate.name(), e);
            }
        }
        return List.of();
    }

    /**
     * @return 形如 {@code Composite[MemoryAuditStore, Slf4jAuditStore]}
     */
    @Override
    public String name() {
        StringBuilder sb = new StringBuilder("Composite[");
        for (int i = 0; i < delegates.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(delegates.get(i).name());
        }
        return sb.append(']').toString();
    }

    /**
     * @return 子存储的不可变视图，顺序即写入顺序
     */
    public List<AuditStore> stores() {
        return delegates;
    }
}
