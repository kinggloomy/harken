package com.harlanking.harken.web.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话表：创建、续期、两种超时、批量失效。
 *
 * <p>超时测试用 40~80ms 的真实等待而不是假时钟：这个类没有注入时钟的口子，
 * 而为了可测性把一个 {@code Instant.now()} 换成 {@code Clock} 值得吗？
 * 在这里不值得 —— 40ms 的等待换来「测的是真实时间路径」，而不是「测的是我传进去的假时间」。
 */
class SessionStoreTest {

    private static SessionStore store() {
        return new SessionStore(Duration.ofMillis(120), Duration.ofMillis(400));
    }

    @Test
    @DisplayName("创建的会话能立刻校验通过，并带回用户名与角色")
    void createAndTouch() {
        SessionStore store = store();
        SessionStore.Session created = store.create("harlan", Role.OPERATOR);

        SessionStore.Session touched = store.touch(created.token());

        assertNotNull(touched);
        assertEquals("harlan", touched.username());
        assertEquals(Role.OPERATOR, touched.role());
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("令牌是随机且不重复的")
    void tokensAreUnique() {
        SessionStore store = store();
        String a = store.create("u", Role.ADMIN).token();
        String b = store.create("u", Role.ADMIN).token();

        assertNotNull(a);
        assertTrue(a.length() >= 40, "32 字节 base64url 至少 43 字符，实际 " + a.length());
        assertFalse(a.equals(b));
    }

    @Test
    @DisplayName("空闲超时后会话失效")
    void idleTimeoutExpires() throws InterruptedException {
        SessionStore store = store();
        String token = store.create("u", Role.ADMIN).token();

        Thread.sleep(160);

        assertNull(store.touch(token));
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("持续访问可以续期，但绝对上限到了照样失效")
    void absoluteTimeoutWins() throws InterruptedException {
        // 给足余量：每次间隔 250ms 远小于空闲超时 800ms，即使机器卡顿也不会误判成空闲超时；
        // 总时长 1500ms 超过绝对上限 1200ms，测的正是「续期也救不回来」这条
        SessionStore store = new SessionStore(Duration.ofMillis(800), Duration.ofMillis(1200));
        String token = store.create("u", Role.ADMIN).token();

        for (int i = 0; i < 6; i++) {
            Thread.sleep(250);
            store.touch(token);
        }

        assertNull(store.touch(token), "绝对上限之后必须失效，否则会话可以无限续命");
    }

    @Test
    @DisplayName("登出后令牌立即失效")
    void invalidate() {
        SessionStore store = store();
        String token = store.create("u", Role.ADMIN).token();

        assertTrue(store.invalidate(token));
        assertNull(store.touch(token));
        assertFalse(store.invalidate(token));
    }

    @Test
    @DisplayName("invalidateAll 让所有会话失效（凭据重载时用）")
    void invalidateAll() {
        SessionStore store = store();
        store.create("a", Role.ADMIN);
        store.create("b", Role.OPERATOR);

        assertEquals(2, store.invalidateAll());
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("invalidateUser 只踢指定用户")
    void invalidateUser() {
        SessionStore store = store();
        String a = store.create("a", Role.ADMIN).token();
        String b = store.create("b", Role.OPERATOR).token();

        assertEquals(1, store.invalidateUser("a"));
        assertNull(store.touch(a));
        assertNotNull(store.touch(b));
    }

    @Test
    @DisplayName("空令牌与未知令牌都返回 null，不抛异常")
    void nullAndUnknownToken() {
        SessionStore store = store();

        assertNull(store.touch(null));
        assertNull(store.touch(""));
        assertNull(store.touch("not-a-token"));
        assertNull(store.peek("not-a-token"));
    }
}
