package com.harlanking.harken.web.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败计数与锁定。
 *
 * <h2>为什么按用户名而不是按 IP</h2>
 * <p>按 IP 锁会把同一个 NAT 后面的同事一起锁死 —— 在办公室里这是高频事故。
 * 按用户名锁，代价是攻击者可以对某个用户名做「锁死」攻击（Denial of Service），
 * 但 Harken 的账号是内部几个人，锁定时间又是有限的（默认 15 分钟），
 * 这个代价可以接受，而误伤同事不可接受。
 *
 * <p>真正的暴力破解防护应该由外层（网关、fail2ban）承担。这里做的是
 * 「防止有人在凌晨三点慢慢试密码直到试对」这一层的最低限度保护。
 *
 * <h2>成功登录要清零</h2>
 * <p>不然一个用户把密码记错四次，某天再错一次就被锁，而他明明一直登录成功。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class LoginThrottle {

    /** 单个计数项。 */
    private static final class Counter {
        int failures;
        Instant lockedUntil;
        Instant lastFailureAt;
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration lockDuration;

    /**
     * @param maxFailures  连续失败多少次触发锁定，必须为正
     * @param lockDuration 锁定多久，必须为正
     */
    public LoginThrottle(int maxFailures, Duration lockDuration) {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("maxFailures 必须为正");
        }
        Objects.requireNonNull(lockDuration, "lockDuration");
        if (lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("lockDuration 必须为正");
        }
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
    }

    /**
     * 是否处于锁定状态。
     *
     * @param key 用户名
     * @return 锁定时返回 {@code true}
     */
    public boolean isLocked(String key) {
        Counter c = counters.get(key);
        if (c == null) {
            return false;
        }
        synchronized (c) {
            if (c.lockedUntil == null) {
                return false;
            }
            if (Instant.now().isBefore(c.lockedUntil)) {
                return true;
            }
            // 锁已到期：整个计数清零，给用户一个干净的重新开始
            counters.remove(key, c);
            return false;
        }
    }

    /**
     * 剩余锁定秒数，未锁定返回 0。用于 {@code Retry-After} 响应头。
     *
     * @param key 用户名
     * @return 秒数
     */
    public long lockedSecondsRemaining(String key) {
        Counter c = counters.get(key);
        if (c == null) {
            return 0;
        }
        synchronized (c) {
            if (c.lockedUntil == null) {
                return 0;
            }
            long secs = Duration.between(Instant.now(), c.lockedUntil).getSeconds();
            return Math.max(0, secs);
        }
    }

    /**
     * 记录一次失败。
     *
     * @param key 用户名
     * @return 当前的连续失败次数（达到阈值的那一次会同时开始锁定）
     */
    public int recordFailure(String key) {
        Counter c = counters.computeIfAbsent(key, k -> new Counter());
        synchronized (c) {
            c.failures++;
            c.lastFailureAt = Instant.now();
            if (c.failures >= maxFailures) {
                c.lockedUntil = Instant.now().plus(lockDuration);
            }
            sweepIfNeeded();
            return c.failures;
        }
    }

    /**
     * 记录一次成功：清零该用户的所有计数。
     *
     * @param key 用户名
     */
    public void recordSuccess(String key) {
        counters.remove(key);
    }

    /**
     * 当前被跟踪的用户名数量（健康检查用）。
     *
     * @return 数量
     */
    public int trackedKeys() {
        return counters.size();
    }

    private void sweepIfNeeded() {
        if (counters.size() < 512) {
            return;
        }
        // 阈值调大后内存里最多留几百条，不会无限增长；这里顺手清掉早已解锁且久未失败的老条目
        Instant cutoff = Instant.now().minus(lockDuration.multipliedBy(4));
        counters.entrySet().removeIf(e -> {
            Counter c = e.getValue();
            synchronized (c) {
                return (c.lockedUntil == null || Instant.now().isAfter(c.lockedUntil))
                        && c.lastFailureAt != null && c.lastFailureAt.isBefore(cutoff);
            }
        });
    }
}
