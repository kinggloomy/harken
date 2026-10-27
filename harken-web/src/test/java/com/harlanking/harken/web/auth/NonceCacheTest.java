package com.harlanking.harken.web.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * nonce 表的行为测试：判重、过期回收、容量上限。
 */
class NonceCacheTest {

    private static final long WINDOW = 60_000L;

    @Test
    @DisplayName("第一次登记通过，第二次判为重放")
    void detectsReplay() {
        NonceCache cache = new NonceCache();
        assertTrue(cache.registerIfAbsent("n-12345678", 1_000L, WINDOW));
        assertFalse(cache.registerIfAbsent("n-12345678", 1_500L, WINDOW));
    }

    @Test
    @DisplayName("窗口过期后同一个 nonce 可以再用（不长期占用内存）")
    void allowsReuseAfterExpiry() {
        NonceCache cache = new NonceCache();
        assertTrue(cache.registerIfAbsent("n-12345678", 1_000L, WINDOW));
        // 时间推进到过期之后
        assertTrue(cache.registerIfAbsent("n-12345678", 1_000L + WINDOW + 1, WINDOW));
        assertEquals(1, cache.size(), "过期条目应被回收，不能只增不减");
    }

    @Test
    @DisplayName("过期条目会被清掉")
    void purgesExpired() {
        NonceCache cache = new NonceCache();
        cache.registerIfAbsent("a-12345678", 0L, 1_000L);
        cache.registerIfAbsent("b-12345678", 500L, 1_000L);
        int removed = cache.purgeExpired(2_000L);
        assertEquals(2, removed);
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("容量满了以后淘汰最老的，不无限增长")
    void evictsOldestWhenFull() {
        NonceCache cache = new NonceCache(2);
        cache.registerIfAbsent("a-12345678", 0L, WINDOW);
        cache.registerIfAbsent("b-12345678", 0L, WINDOW);
        assertEquals(2, cache.size());
        assertTrue(cache.registerIfAbsent("c-12345678", 1L, WINDOW));
        assertEquals(2, cache.size(), "容量必须被守住");

        // 最老的 a 被淘汰 → 它现在「看起来是新的」。这是容量上限换来的代价，
        // 已写在类注释里：极端压力下宁可放过一个重放，也不 OOM
        assertTrue(cache.registerIfAbsent("a-12345678", 2L, WINDOW));
        assertFalse(cache.registerIfAbsent("c-12345678", 2L, WINDOW), "最近的仍在表里，仍能判重放");
    }

    @Test
    @DisplayName("容量必须为正")
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new NonceCache(0));
        assertThrows(IllegalArgumentException.class, () -> new NonceCache(-1));
    }
}
