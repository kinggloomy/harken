package com.harlanking.harken.web.auth;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已用过的 nonce 表，用来判重放。
 *
 * <h2>为什么必须有容量上限</h2>
 * <p>「记住 60 秒内所有 nonce」听着无害，实际是一条内存耗尽路径：调用方只要
 * 每秒发几千个不同 nonce（合法 Token 前提下），表就无限长。所以这里：
 * <ul>
 *   <li>每次登记前先清掉<b>已过期</b>的条目（过期的 nonce 本来就会被时间窗拒绝，
 *       留着没有意义）；</li>
 *   <li>清完仍超上限时，按插入顺序淘汰最老的 —— 代价是「极端压力下可能放过一个本应
 *       拦截的重放」，收益是「不会因为被刷而 OOM」。在两者之间选不宕机。</li>
 * </ul>
 *
 * <p><b>为什么不给 nonce 用布隆过滤器</b>：布隆过滤器不能删元素，时间窗一过就没法回收；
 * 要回收就得换窗口，实现复杂度反而更高。这里条目数量级是「窗口内请求数」，
 * 一个几百 KB 的 LinkedHashMap 足够。
 *
 * <p>全部方法 {@code synchronized}：判重必须是原子操作。「先查再放」两个分开的调用
 * 会让两个并发请求都通过 —— 那正是重放窗口。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class NonceCache {

    /** 默认容量：60 秒窗口内允许的最大不同 nonce 数。 */
    public static final int DEFAULT_MAX_ENTRIES = 20_000;

    private final int maxEntries;
    private final Map<String, Long> expiresAt;

    /** 构造器，使用默认容量。 */
    public NonceCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * @param maxEntries 容量上限，必须为正
     */
    public NonceCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries 必须为正：" + maxEntries);
        }
        this.maxEntries = maxEntries;
        // accessOrder=false：淘汰按插入顺序，业务上「最老的 nonce 最先过期」，正好吻合
        this.expiresAt = new LinkedHashMap<>();
    }

    /**
     * 登记一个 nonce。
     *
     * @param nonce       一次性随机串
     * @param nowMillis   当前时间（毫秒）
     * @param windowMillis 有效期（毫秒）
     * @return {@code true} 表示首次出现（可用）；{@code false} 表示重放
     */
    public synchronized boolean registerIfAbsent(String nonce, long nowMillis, long windowMillis) {
        purgeExpired(nowMillis);
        Long existing = expiresAt.get(nonce);
        if (existing != null && existing > nowMillis) {
            return false;
        }
        if (expiresAt.size() >= maxEntries) {
            Iterator<String> it = expiresAt.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        expiresAt.put(nonce, nowMillis + windowMillis);
        return true;
    }

    /**
     * 清掉过期条目。
     *
     * @param nowMillis 当前时间（毫秒）
     * @return 本次清掉的条数
     */
    public synchronized int purgeExpired(long nowMillis) {
        int removed = 0;
        Iterator<Map.Entry<String, Long>> it = expiresAt.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= nowMillis) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * @return 当前条目数（测试与诊断用）
     */
    public synchronized int size() {
        return expiresAt.size();
    }

    /**
     * @return 容量上限
     */
    public int maxEntries() {
        return maxEntries;
    }
}
