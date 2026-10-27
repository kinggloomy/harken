package com.harlanking.harken.web.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签名算法本身的测试。
 *
 * <p>重点在两件容易做错的事：<b>待签串不能有歧义</b>、<b>任何一处变化都必须改变签名</b>。
 */
class RequestSignerTest {

    private static final String SECRET = "s3cr3t-shared-key";
    private static final byte[] BODY = "{\"script\":\"return 1\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("待签串是固定五段，用 \\n 分隔")
    void canonicalFormat() {
        String c = RequestSigner.canonical("post", "/harken/ai/query", "1700000000", "nonce-12345678", BODY);
        String[] parts = c.split("\n", -1);
        assertEquals(5, parts.length, "必须是五段：" + c);
        assertEquals("POST", parts[0], "方法统一大写");
        assertEquals("/harken/ai/query", parts[1]);
        assertEquals("1700000000", parts[2]);
        assertEquals("nonce-12345678", parts[3]);
        assertEquals(64, parts[4].length(), "最后一段是 64 位十六进制 SHA-256");
    }

    @Test
    @DisplayName("待签串无歧义：字段挪位不会撞串")
    void canonicalHasNoAmbiguity() {
        // 如果把字段直接拼接（不用换行），这两组会得到同一个串
        String a = RequestSigner.canonical("POST", "/ab", "1", "c", new byte[0]);
        String b = RequestSigner.canonical("POST", "/a", "b1", "c", new byte[0]);
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("同样的输入必须得到同样的签名（可复现）")
    void signIsDeterministic() {
        String s1 = RequestSigner.sign(SECRET, "POST", "/harken/ai/query", "1700000000", "n-12345678", BODY);
        String s2 = RequestSigner.sign(SECRET, "POST", "/harken/ai/query", "1700000000", "n-12345678", BODY);
        assertEquals(s1, s2);
        assertEquals(64, s1.length());
        assertEquals(s1.toLowerCase(), s1, "输出统一小写，避免比较时大小写踩坑");
    }

    @Test
    @DisplayName("验签通过")
    void verifyOk() {
        String sig = RequestSigner.sign(SECRET, "POST", "/harken/ai/query", "1700000000", "n-12345678", BODY);
        assertTrue(RequestSigner.verify(SECRET, "POST", "/harken/ai/query", "1700000000", "n-12345678", BODY, sig));
    }

    @Test
    @DisplayName("签名大小写不敏感（十六进制）")
    void verifyIgnoresHexCase() {
        String sig = RequestSigner.sign(SECRET, "POST", "/harken/ai/query", "1700000000", "n-12345678", BODY);
        assertTrue(RequestSigner.verify(SECRET, "POST", "/harken/ai/query", "1700000000", "n-12345678",
                BODY, sig.toUpperCase()));
    }

    @Test
    @DisplayName("改 body / 换方法 / 换路径 / 换 nonce / 换时间戳，全部验签失败")
    void anyTamperFails() {
        String ts = "1700000000";
        String nonce = "n-12345678";
        String path = "/harken/ai/query";
        String sig = RequestSigner.sign(SECRET, "POST", path, ts, nonce, BODY);

        byte[] tampered = "{\"script\":\"return 2\"}".getBytes(StandardCharsets.UTF_8);
        assertFalse(RequestSigner.verify(SECRET, "POST", path, ts, nonce, tampered, sig), "body 被改");
        assertFalse(RequestSigner.verify(SECRET, "GET", path, ts, nonce, BODY, sig), "方法被改");
        assertFalse(RequestSigner.verify(SECRET, "POST", "/harken/ai/capabilities", ts, nonce, BODY, sig),
                "路径被改");
        assertFalse(RequestSigner.verify(SECRET, "POST", path, ts, "n-87654321", BODY, sig), "nonce 被改");
        assertFalse(RequestSigner.verify(SECRET, "POST", path, "1700000001", nonce, BODY, sig), "时间戳被改");
        assertFalse(RequestSigner.verify("other-secret", "POST", path, ts, nonce, BODY, sig), "密钥不对");
    }

    @Test
    @DisplayName("空 body 与 null body 等价（GET 场景）")
    void emptyBodyEqualsNullBody() {
        String a = RequestSigner.sha256Hex(new byte[0]);
        String b = RequestSigner.sha256Hex(null);
        assertEquals(a, b);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", a,
                "空串的 SHA-256 是固定值，用来防止「摘要实现被换掉」");
    }
}
