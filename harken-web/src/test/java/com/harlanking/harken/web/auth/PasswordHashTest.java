package com.harlanking.harken.web.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码哈希的行为约定。
 *
 * <p>迭代次数统一用 1000 而不是默认的 12 万：这里测的是<b>逻辑</b>，
 * 用默认值会让每个用例多花 100ms，整个模块慢出一个量级而什么都没多验证。
 */
class PasswordHashTest {

    private static final int FAST = 1_000;

    @Test
    @DisplayName("哈希后能校验通过，错误密码不通过")
    void hashAndVerify() {
        String stored = PasswordHash.hash("s3cret-密码", FAST);

        assertTrue(PasswordHash.isHashed(stored));
        assertTrue(PasswordHash.verify("s3cret-密码", stored));
        assertFalse(PasswordHash.verify("s3cret-密码 ", stored));
        assertFalse(PasswordHash.verify("", stored));
    }

    @Test
    @DisplayName("同一个密码两次哈希结果不同（盐是随机的）")
    void saltIsRandom() {
        String a = PasswordHash.hash("same", FAST);
        String b = PasswordHash.hash("same", FAST);

        assertNotEquals(a, b);
        assertTrue(PasswordHash.verify("same", a));
        assertTrue(PasswordHash.verify("same", b));
    }

    @Test
    @DisplayName("迭代次数写在串里，校验时以串里为准")
    void iterationsComeFromStoredValue() {
        String stored = PasswordHash.hash("pw", FAST);
        assertTrue(stored.startsWith("pbkdf2-sha256$1000$"), stored);
        assertTrue(PasswordHash.verify("pw", stored));
    }

    @Test
    @DisplayName("明文配置能校验，并且 isHashed 会如实说它不是哈希")
    void plaintextStillWorks() {
        assertFalse(PasswordHash.isHashed("plain-password"));
        assertTrue(PasswordHash.verify("plain-password", "plain-password"));
        assertFalse(PasswordHash.verify("other", "plain-password"));
    }

    @Test
    @DisplayName("哈希串格式损坏时返回 false，绝不抛异常（认证路径上异常=放行的风险）")
    void corruptedHashReturnsFalse() {
        String broken = "pbkdf2-sha256$notanumber$c2FsdA==$aGFzaA==";
        assertFalse(PasswordHash.verify("whatever", broken));

        assertFalse(PasswordHash.verify("whatever", "pbkdf2-sha256$1000$!!notbase64!!$xx"));
        assertFalse(PasswordHash.verify("whatever", "pbkdf2-sha256$1000$c2FsdA=="));
        assertFalse(PasswordHash.verify(null, PasswordHash.hash("x", FAST)));
        assertFalse(PasswordHash.verify("x", null));
    }
}
