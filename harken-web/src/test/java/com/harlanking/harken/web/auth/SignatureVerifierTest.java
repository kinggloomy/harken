package com.harlanking.harken.web.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验器测试：这一步把「时间窗、签名、nonce」三件事的顺序与语义钉死。
 *
 * <p>测试里显式传入 {@code nowMillis}，而不是让代码读 {@code System.currentTimeMillis()} ——
 * 时间相关的逻辑如果依赖真实时钟，测试就会在凌晨、闰秒、CI 机器时区不同的地方随机飘。
 */
class SignatureVerifierTest {

    private static final String SECRET = "shared-secret";
    private static final String PATH = "/harken/ai/query";
    private static final byte[] BODY = "{\"script\":\"return 1\"}".getBytes(StandardCharsets.UTF_8);
    private static final long NOW = 1_700_000_000_000L;

    private static String ts(long nowMillis) {
        return String.valueOf(nowMillis / 1000L);
    }

    private static SignatureVerifier verifier() {
        return new SignatureVerifier(SECRET, 60);
    }

    private static String sign(String nonce, long nowMillis) {
        return RequestSigner.sign(SECRET, "POST", PATH, ts(nowMillis), nonce, BODY);
    }

    @Test
    @DisplayName("未配置密钥时一切放行（enabled=false）")
    void disabledPassesEverything() {
        SignatureVerifier v = SignatureVerifier.disabled();
        assertFalse(v.enabled());
        SignatureVerifier.Verdict r = v.verify("POST", PATH, null, null, null, BODY, NOW);
        assertTrue(r.ok());
        assertEquals(SignatureVerifier.Status.OK, r.status());
    }

    @Test
    @DisplayName("正常签名通过")
    void ok() {
        long now = NOW;
        SignatureVerifier.Verdict r = verifier().verify("POST", PATH, ts(now), "n-12345678",
                sign("n-12345678", now), BODY, now);
        assertEquals(SignatureVerifier.Status.OK, r.status(), r.message());
    }

    @Test
    @DisplayName("缺任一头 → MISSING")
    void missingHeaders() {
        SignatureVerifier v = verifier();
        assertEquals(SignatureVerifier.Status.MISSING,
                v.verify("POST", PATH, null, "n-12345678", "abc", BODY, NOW).status());
        assertEquals(SignatureVerifier.Status.MISSING,
                v.verify("POST", PATH, ts(NOW), null, "abc", BODY, NOW).status());
        assertEquals(SignatureVerifier.Status.MISSING,
                v.verify("POST", PATH, ts(NOW), "n-12345678", null, BODY, NOW).status());
    }

    @Test
    @DisplayName("nonce 太短或含空白 → MISSING（形状不对就是调用方实现有问题）")
    void rejectsBadNonceShape() {
        SignatureVerifier v = verifier();
        assertEquals(SignatureVerifier.Status.MISSING,
                v.verify("POST", PATH, ts(NOW), "short", "x", BODY, NOW).status());
        assertEquals(SignatureVerifier.Status.MISSING,
                v.verify("POST", PATH, ts(NOW), "has space 12345", "x", BODY, NOW).status());
    }

    @Test
    @DisplayName("时间戳不是数字 → BAD_TIMESTAMP")
    void badTimestamp() {
        SignatureVerifier.Verdict r = verifier().verify("POST", PATH, "not-a-number",
                "n-12345678", "sig", BODY, NOW);
        assertEquals(SignatureVerifier.Status.BAD_TIMESTAMP, r.status());
    }

    @Test
    @DisplayName("时间戳超出窗口 → EXPIRED（过去与将来都算）")
    void expiredWindow() {
        SignatureVerifier v = verifier();
        long old = NOW - 61_000L;
        assertEquals(SignatureVerifier.Status.EXPIRED,
                v.verify("POST", PATH, ts(old), "n-12345678", sign("n-12345678", old), BODY, NOW).status());

        long future = NOW + 61_000L;
        assertEquals(SignatureVerifier.Status.EXPIRED,
                v.verify("POST", PATH, ts(future), "n-12345678", sign("n-12345678", future), BODY, NOW).status());
    }

    @Test
    @DisplayName("窗口边界内（±60s）仍通过")
    void boundaryStillOk() {
        SignatureVerifier v = verifier();
        long edge = NOW - 60_000L;
        assertEquals(SignatureVerifier.Status.OK,
                v.verify("POST", PATH, ts(edge), "n-12345678", sign("n-12345678", edge), BODY, NOW).status());
    }

    @Test
    @DisplayName("签名不对 → BAD_SIGNATURE，且不会占用 nonce")
    void badSignatureDoesNotBurnNonce() {
        SignatureVerifier v = verifier();
        SignatureVerifier.Verdict bad = v.verify("POST", PATH, ts(NOW), "n-12345678", "deadbeef", BODY, NOW);
        assertEquals(SignatureVerifier.Status.BAD_SIGNATURE, bad.status());

        // 关键：伪造签名不能把受害者的 nonce「烧掉」（否则是 DoS）
        SignatureVerifier.Verdict good = v.verify("POST", PATH, ts(NOW), "n-12345678",
                sign("n-12345678", NOW), BODY, NOW);
        assertEquals(SignatureVerifier.Status.OK, good.status(), good.message());
    }

    @Test
    @DisplayName("同一 nonce 第二次 → REPLAY")
    void replayDetected() {
        SignatureVerifier v = verifier();
        String sig = sign("n-12345678", NOW);
        assertEquals(SignatureVerifier.Status.OK,
                v.verify("POST", PATH, ts(NOW), "n-12345678", sig, BODY, NOW).status());
        SignatureVerifier.Verdict second = v.verify("POST", PATH, ts(NOW), "n-12345678", sig, BODY, NOW);
        assertEquals(SignatureVerifier.Status.REPLAY, second.status());
        assertTrue(second.message().contains("nonce"), second.message());
    }

    @Test
    @DisplayName("过期请求不会污染 nonce 表")
    void expiredRequestDoesNotTouchNonceTable() {
        SignatureVerifier v = verifier();
        long old = NOW - 120_000L;
        v.verify("POST", PATH, ts(old), "n-old-1234", sign("n-old-1234", old), BODY, NOW);
        // 同一个 nonce 现在用于一个新鲜请求，应当被接受（说明上一次没登记）
        assertEquals(SignatureVerifier.Status.OK,
                v.verify("POST", PATH, ts(NOW), "n-old-1234", sign("n-old-1234", NOW), BODY, NOW).status());
    }
}
