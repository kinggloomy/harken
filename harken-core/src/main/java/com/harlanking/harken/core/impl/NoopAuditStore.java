package com.harlanking.harken.core.impl;

import com.harlanking.harken.core.spi.AuditRecord;
import com.harlanking.harken.core.spi.AuditStore;

/**
 * 什么都不记。开发环境用。
 *
 * <p>生产环境<b>不要</b>用这个：没有审计记录，等于出事后无法回答「谁在什么时候改了什么」。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class NoopAuditStore implements AuditStore {

    /** 全局唯一实例，无状态。 */
    public static final NoopAuditStore INSTANCE = new NoopAuditStore();

    private NoopAuditStore() {
    }

    /**
     * 有意留空。
     *
     * @param record 被丢弃的记录
     */
    @Override
    public void save(AuditRecord record) {
        // intentionally empty
    }
}
