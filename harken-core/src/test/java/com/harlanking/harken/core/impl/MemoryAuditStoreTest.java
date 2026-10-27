package com.harlanking.harken.core.impl;

import com.harlanking.harken.core.spi.AuditRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存环形缓冲：满了覆盖最旧、查询最新在前、脚本只留预览。
 */
class MemoryAuditStoreTest {

    private static AuditRecord record(String trace) {
        return AuditRecord.builder().traceId(trace).operator("harlan").script("return 1").build();
    }

    @Test
    void keepsOnlyTheMostRecentRecords() {
        MemoryAuditStore store = new MemoryAuditStore(3);
        IntStream.rangeClosed(1, 5).forEach(i -> store.save(record("t" + i)));

        assertEquals(3, store.size());
        assertEquals(5, store.totalRecorded(), "累计计数不随覆盖减少：它表示历史上有多少次执行");

        List<AuditRecord> recent = store.query(10);
        assertEquals(List.of("t5", "t4", "t3"),
                recent.stream().map(AuditRecord::traceId).toList(),
                "最新在前 —— 面板要的是「刚才那次」");
    }

    @Test
    void queryRespectsLimitAndReturnsImmutableList() {
        MemoryAuditStore store = new MemoryAuditStore(10);
        store.save(record("a"));
        store.save(record("b"));

        assertEquals(1, store.query(1).size());
        assertEquals("b", store.query(1).get(0).traceId());
        assertEquals(0, store.query(0).size());
        assertEquals(0, store.query(-5).size());
        assertTrue(store.query(10).size() <= 2);
    }

    @Test
    void emptyStoreReturnsEmptyListNotException() {
        MemoryAuditStore store = new MemoryAuditStore();
        assertTrue(store.query(5).isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    void longScriptIsTrimmedButDigestSurvives() {
        char[] chars = new char[10_000];
        java.util.Arrays.fill(chars, 'x');
        String big = new String(chars);

        MemoryAuditStore store = new MemoryAuditStore(10, 1_000);
        store.save(AuditRecord.builder().traceId("big").script(big)
                .scriptDigest("sha256:abc").build());

        AuditRecord stored = store.query(1).get(0);
        assertTrue(stored.script().length() < big.length(), "内存副本必须截断，否则大脚本会常驻内存");
        assertTrue(stored.script().startsWith("x".repeat(1_000)));
        assertTrue(stored.script().contains("已截断"));
        // 摘要不能被截掉：它是「同一段脚本跑了多少次」的依据
        assertEquals("sha256:abc", stored.scriptDigest());
    }

    @Test
    void shortScriptIsStoredAsIs() {
        MemoryAuditStore store = new MemoryAuditStore(10, 4_096);
        store.save(AuditRecord.builder().traceId("s").script("return 1+1").build());
        assertEquals("return 1+1", store.query(1).get(0).script());
    }

    @Test
    void nullRecordIsIgnored() {
        MemoryAuditStore store = new MemoryAuditStore();
        store.save(null);
        assertEquals(0, store.size());
    }

    @Test
    void clearKeepsTotals() {
        MemoryAuditStore store = new MemoryAuditStore(5);
        store.save(record("a"));
        store.save(record("b"));
        store.clear();

        assertEquals(0, store.size());
        assertTrue(store.query(5).isEmpty());
        assertEquals(2, store.totalRecorded());
    }

    @Test
    void describesItselfAsQueryable() {
        MemoryAuditStore store = new MemoryAuditStore();
        assertTrue(store.supportsQuery());
        assertEquals("MemoryAuditStore", store.name());
        assertEquals(200, new MemoryAuditStore().capacity(), "默认容量 200：够覆盖最近一小时，又不吃内存");
        assertNull(record("x").errorType());
    }

    @Test
    void capacityIsClampedToOne() {
        MemoryAuditStore store = new MemoryAuditStore(0);
        store.save(record("a"));
        store.save(record("b"));
        assertEquals(1, store.size());
        assertFalse(store.query(5).isEmpty());
    }
}
