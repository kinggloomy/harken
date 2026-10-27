package com.harlanking.harken.core.impl;

import com.harlanking.harken.core.spi.AuditRecord;
import com.harlanking.harken.core.spi.AuditStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组合审计：一路坏了不能带着其他路一起哑掉；查询只认一个来源。
 */
class CompositeAuditStoreTest {

    /** 只记内存的测试替身。 */
    private static final class Recorder implements AuditStore {
        private final List<AuditRecord> saved = new ArrayList<>();
        private final String name;
        private final boolean failOnSave;
        private final boolean queryable;

        private Recorder(String name, boolean failOnSave, boolean queryable) {
            this.name = name;
            this.failOnSave = failOnSave;
            this.queryable = queryable;
        }

        @Override
        public void save(AuditRecord record) {
            if (failOnSave) {
                throw new IllegalStateException(name + " 写失败");
            }
            saved.add(record);
        }

        @Override
        public boolean supportsQuery() {
            return queryable;
        }

        @Override
        public List<AuditRecord> query(int limit) {
            return saved.subList(0, Math.min(limit, saved.size()));
        }

        @Override
        public String name() {
            return name;
        }
    }

    private static AuditRecord record() {
        return AuditRecord.builder().traceId("t1").script("return 1").build();
    }

    @Test
    void savesToEveryDelegate() {
        Recorder a = new Recorder("A", false, false);
        Recorder b = new Recorder("B", false, false);
        CompositeAuditStore composite = new CompositeAuditStore(a, b);

        composite.save(record());

        assertEquals(1, a.saved.size());
        assertEquals(1, b.saved.size());
    }

    @Test
    void oneFailingDelegateDoesNotStopTheOthers() {
        Recorder broken = new Recorder("broken", true, false);
        Recorder healthy = new Recorder("healthy", false, false);
        CompositeAuditStore composite = new CompositeAuditStore(broken, healthy);

        composite.save(record());

        // 数据库审计表被锁了，不该连带让日志也没写下来
        assertEquals(1, healthy.saved.size(), "坏掉的那一路不能把好的一路带走");
    }

    @Test
    void queryUsesFirstQueryableDelegateAndDoesNotMerge() {
        Recorder memory = new Recorder("memory", false, true);
        Recorder logs = new Recorder("logs", false, false);
        CompositeAuditStore composite = new CompositeAuditStore(memory, logs);

        composite.save(record());

        assertTrue(composite.supportsQuery());
        assertEquals(1, composite.query(10).size());
        assertEquals("t1", composite.query(10).get(0).traceId());
        // 写入是扇出的：两路都要有；查询只取第一个支持查询的
        assertEquals(1, logs.saved.size(), "写仍然扇出到日志一路");
    }

    @Test
    void querySkippedWhenNothingIsQueryable() {
        CompositeAuditStore composite = new CompositeAuditStore(
                new Recorder("logs", false, false), new Recorder("noop", false, false));

        assertFalse(composite.supportsQuery());
        assertTrue(composite.query(10).isEmpty(), "不支持查询时返回空列表，而不是抛 UnsupportedOperationException");
    }

    @Test
    void emptyCompositeIsRejected() {
        // 空组合看起来像"配了审计"，实际一条也写不进去 —— 这是最危险的状态
        assertThrows(IllegalArgumentException.class, CompositeAuditStore::new);
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeAuditStore((AuditStore[]) null));
        assertThrows(IllegalArgumentException.class,
                () -> new CompositeAuditStore((AuditStore) null));
    }

    @Test
    void nullElementsAreIgnoredButOrderIsKept() {
        Recorder a = new Recorder("A", false, false);
        Recorder b = new Recorder("B", false, false);
        CompositeAuditStore composite = new CompositeAuditStore(a, null, b);

        assertEquals(List.of("A", "B"),
                composite.stores().stream().map(AuditStore::name).toList());
        assertEquals("Composite[A, B]", composite.name());
    }
}
