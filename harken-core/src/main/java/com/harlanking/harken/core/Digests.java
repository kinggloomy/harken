package com.harlanking.harken.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 摘要工具。脚本编译缓存的 key 与审计记录里的摘要共用同一套算法。
 *
 * <p>用 SHA-256 而不是 {@code String.hashCode()}：缓存 key 需要足够低的碰撞概率，
 * 一旦两个不同脚本撞到同一个 key，执行的就是别人的代码。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class Digests {

    private Digests() {
    }

    /**
     * 计算 UTF-8 文本的 SHA-256，输出 64 位小写十六进制。
     *
     * @param text 原文，不能为 {@code null}
     * @return 64 字符十六进制串
     * @throws IllegalStateException 运行环境缺少 SHA-256（按 JLS 不可能发生）
     */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 取 SHA-256 的前 {@code length} 位，用于日志与审计里「够用就好」的场景。
     *
     * <p>{@code length} 超过 64 时返回完整摘要，不会抛异常。
     *
     * @param text   原文
     * @param length 需要的字符数
     * @return 截短后的十六进制串
     */
    public static String shortSha256(String text, int length) {
        String full = sha256(text);
        return full.substring(0, Math.min(length, full.length()));
    }
}
