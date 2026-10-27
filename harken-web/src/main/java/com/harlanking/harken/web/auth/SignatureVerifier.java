package com.harlanking.harken.web.auth;

import java.time.Instant;

/**
 * 请求签名校验器：时间窗 + 签名 + nonce 三件事的组合，顺序是安全相关的。
 *
 * <h2>「配了密钥就必须签名」——没有中间态</h2>
 * <p>这是刻意设计的：{@link #enabled()} 只取决于<b>是否配置了密钥</b>。
 * 不存在「配了密钥但仍允许裸 Token」这种配置，因为那等于给使用者一个
 * 「我以为开了」的假象，比不开更危险。
 *
 * <h2>校验顺序为什么不能换</h2>
 * <ol>
 *   <li><b>先看时间窗</b>：过期的请求直接拒绝，连 nonce 表都不必碰 ——
 *       否则一个用旧请求刷接口的人能把 nonce 表刷满，反而把正常请求挤掉。</li>
 *   <li><b>再验签名</b>：签名不对的请求是「攻击者」，绝不能让它往 nonce 表里写东西 ——
 *       否则攻击者可以用伪造签名抢先「占用」受害者的 nonce，造成合法请求被判重放（DoS）。</li>
 *   <li><b>最后登记 nonce</b>：到这一步请求已经是可信的，登记才有意义。</li>
 * </ol>
 * <p>顺序反过来（先登记 nonce）是个典型错误：连验证都没过就写状态，等于把状态机交给攻击者。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class SignatureVerifier {

    /** 默认时间窗：60 秒。计划书 SR-04 的原文要求。 */
    public static final int DEFAULT_WINDOW_SECONDS = 60;

    /**
     * 校验结果。
     *
     * @author harlan
     * @since 1.0.0
     */
    public enum Status {
        /** 通过。 */
        OK,
        /** 缺少必要请求头。 */
        MISSING,
        /** 时间戳不是合法数字。 */
        BAD_TIMESTAMP,
        /** 时间戳超出允许窗口。 */
        EXPIRED,
        /** 签名不匹配。 */
        BAD_SIGNATURE,
        /** nonce 已被用过（重放）。 */
        REPLAY
    }

    /**
     * 一次校验的结论。
     *
     * @param status  结论
     * @param message 给人看的说明（会出现在 401 响应里，不含密钥等敏感信息）
     */
    public record Verdict(Status status, String message) {

        /** @return 是否通过 */
        public boolean ok() {
            return status == Status.OK;
        }
    }

    private static final SignatureVerifier DISABLED =
            new SignatureVerifier("", DEFAULT_WINDOW_SECONDS);

    private final String secret;
    private final int windowSeconds;
    private final NonceCache nonces;

    /**
     * @param secret        共享密钥；空或 {@code null} 表示未启用
     * @param windowSeconds 时间窗（秒），必须为正
     */
    public SignatureVerifier(String secret, int windowSeconds) {
        this.secret = secret == null ? "" : secret;
        this.windowSeconds = windowSeconds > 0 ? windowSeconds : DEFAULT_WINDOW_SECONDS;
        this.nonces = new NonceCache();
    }

    /**
     * @return 未启用的实例（所有请求视为不要求签名）
     */
    public static SignatureVerifier disabled() {
        return DISABLED;
    }

    /**
     * @return {@code true} 表示已配置密钥，所有 AI 请求都必须带签名
     */
    public boolean enabled() {
        return !secret.isEmpty();
    }

    /**
     * @return 时间窗（秒）
     */
    public int windowSeconds() {
        return windowSeconds;
    }

    /**
     * 校验。
     *
     * @param method    HTTP 方法
     * @param path      请求路径
     * @param timestamp {@code X-Harken-Timestamp}，秒级 epoch
     * @param nonce     {@code X-Harken-Nonce}，8-200 个非空白字符
     * @param signature {@code X-Harken-Signature}，小写十六进制
     * @param body      请求体原始字节
     * @param nowMillis 当前时间（毫秒），由调用方传入便于测试
     * @return 结论
     */
    public Verdict verify(String method, String path, String timestamp, String nonce,
                          String signature, byte[] body, long nowMillis) {
        if (!enabled()) {
            return new Verdict(Status.OK, "签名未启用");
        }
        if (isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            return new Verdict(Status.MISSING,
                    "缺少签名请求头。需要 X-Harken-Timestamp / X-Harken-Nonce / X-Harken-Signature。");
        }
        if (nonce.length() < 8 || nonce.length() > 200 || nonce.chars().anyMatch(Character::isWhitespace)) {
            // nonce 会被当作 Map 的键，长度与字符集必须收窄：太短容易碰撞，太长/带空白
            // 说明调用方实现有问题，早点报错比默默接受好
            return new Verdict(Status.MISSING, "nonce 必须是 8-200 个非空白字符。");
        }

        long nowSeconds = nowMillis / 1000L;
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return new Verdict(Status.BAD_TIMESTAMP,
                    "X-Harken-Timestamp 必须是秒级 epoch 数字，实际收到：" + abbreviate(timestamp));
        }

        long skew = Math.abs(nowSeconds - ts);
        if (skew > windowSeconds) {
            return new Verdict(Status.EXPIRED,
                    "请求时间与服务器相差 " + skew + " 秒，超过 " + windowSeconds
                            + " 秒窗口。请校准系统时钟（" + Instant.ofEpochSecond(ts) + " vs now "
                            + Instant.ofEpochSecond(nowSeconds) + "）。");
        }

        if (!RequestSigner.verify(secret, method, path, timestamp, nonce, body, signature)) {
            return new Verdict(Status.BAD_SIGNATURE,
                    "签名校验失败。请确认待签串是 METHOD\\nPATH\\nTIMESTAMP\\nNONCE\\nHEX(SHA256(body))。");
        }

        if (!nonces.registerIfAbsent(nonce.trim(), nowMillis, windowSeconds * 1000L)) {
            return new Verdict(Status.REPLAY,
                    "该 nonce 已在 " + windowSeconds + " 秒内使用过，判定为重放。每次请求必须换新的 nonce。");
        }
        return new Verdict(Status.OK, "通过");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String abbreviate(String s) {
        String t = s == null ? "null" : s.trim();
        return t.length() <= 32 ? t : t.substring(0, 32) + "…";
    }
}
