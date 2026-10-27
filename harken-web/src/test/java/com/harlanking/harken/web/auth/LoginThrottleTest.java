package com.harlanking.harken.web.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败锁定：阈值、清零、到期解锁。
 */
class LoginThrottleTest {

    private static LoginThrottle throttle(int maxFailures, long lockMillis) {
        return new LoginThrottle(maxFailures, Duration.ofMillis(lockMillis));
    }

    @Test
    @DisplayName("达到阈值才锁定，差一次不锁")
    void locksAtThreshold() {
        LoginThrottle t = throttle(3, 5_000);

        t.recordFailure("harlan");
        t.recordFailure("harlan");
        assertFalse(t.isLocked("harlan"), "两次失败不该锁");

        t.recordFailure("harlan");
        assertTrue(t.isLocked("harlan"));
    }

    @Test
    @DisplayName("成功登录清零计数")
    void successResetsCounter() {
        LoginThrottle t = throttle(3, 5_000);

        t.recordFailure("harlan");
        t.recordFailure("harlan");
        t.recordSuccess("harlan");
        t.recordFailure("harlan");
        t.recordFailure("harlan");

        assertFalse(t.isLocked("harlan"), "清零之后重新计数，两次仍不该锁");
    }

    @Test
    @DisplayName("锁定到期后自动解锁，且计数归零")
    void unlocksAfterDuration() throws InterruptedException {
        LoginThrottle t = throttle(2, 120);

        t.recordFailure("harlan");
        t.recordFailure("harlan");
        assertTrue(t.isLocked("harlan"));

        Thread.sleep(180);
        assertFalse(t.isLocked("harlan"));

        // 解锁后从零开始：再失败一次不该立刻又锁
        t.recordFailure("harlan");
        assertFalse(t.isLocked("harlan"));
    }

    @Test
    @DisplayName("锁定期间返回剩余秒数，供 Retry-After 使用")
    void reportsRemainingSeconds() {
        LoginThrottle t = throttle(1, 10_000);
        t.recordFailure("harlan");

        long remaining = t.lockedSecondsRemaining("harlan");
        assertTrue(remaining > 0 && remaining <= 10, "剩余秒数应在 1..10，实际 " + remaining);
    }

    @Test
    @DisplayName("按用户名隔离：锁了 A 不影响 B")
    void isolatedPerUser() {
        LoginThrottle t = throttle(1, 5_000);
        t.recordFailure("a");

        assertTrue(t.isLocked("a"));
        assertFalse(t.isLocked("b"));
        assertEquals(0, t.lockedSecondsRemaining("b"));
    }
}
