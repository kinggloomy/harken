package com.harlanking.harken.web.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * 密码的哈希与校验。用 JDK 自带的 PBKDF2，不引 bcrypt 库。
 *
 * <h2>为什么是 PBKDF2WithHmacSHA256</h2>
 * <p>JDK 自带、无需第三方依赖、FIPS 认可，而且「慢」这个关键属性可以靠迭代次数调。
 * 相比之下把 bcrypt 打进 jar 要连带处理 native 或纯 Java 实现的版本问题。
 * 对「一个内部运维控制台」的威胁模型，PBKDF2 + 12 万次迭代足够。
 *
 * <h2>存储格式</h2>
 * <pre>pbkdf2-sha256$120000$&lt;salt-base64&gt;$&lt;hash-base64&gt;</pre>
 * <p>迭代次数写在串里，所以以后调高迭代次数不会让旧密码全部失效 ——
 * 校验时以串里记的为准。这是「可演进的密码存储」最基本的要求。
 *
 * <h2>明文兼容</h2>
 * <p>配置里直接写明文密码是被允许的（有人就是要在测试环境图方便），
 * 但会被 {@link #isHashed} 识别出来并在启动日志里 WARN。
 * 不允许明文是更安全的做法，但结果是用户把密码写在别处再抄过来，
 * 反而更难审计。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class PasswordHash {

    /** 默认迭代次数。2024 年后新建系统的常见下限是 10 万。 */
    public static final int DEFAULT_ITERATIONS = 120_000;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHash() {
    }

    /**
     * 生成哈希串，可直接写进配置文件。
     *
     * @param plain 明文密码
     * @return {@code pbkdf2-sha256$<迭代>$<盐>$<哈希>}
     */
    public static String hash(String plain) {
        return hash(plain, DEFAULT_ITERATIONS);
    }

    /**
     * 生成哈希串，指定迭代次数（测试用低迭代加速）。
     *
     * @param plain      明文密码
     * @param iterations 迭代次数，必须 {@code >= 1}
     * @return 哈希串
     */
    public static String hash(String plain, int iterations) {
        if (plain == null || plain.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (iterations < 1) {
            throw new IllegalArgumentException("迭代次数必须为正数");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(plain.toCharArray(), salt, iterations);
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    /**
     * 判断一个配置值是不是哈希串。
     *
     * @param secret 配置里的密码值
     * @return {@code true} 表示已是哈希，{@code false} 表示是明文（需要 WARN）
     */
    public static boolean isHashed(String secret) {
        return secret != null && secret.startsWith(PREFIX + "$");
    }

    /**
     * 校验密码。
     *
     * <p>对哈希串走 PBKDF2；对明文走常量时间比较。两条路径都用
     * {@link MessageDigest#isEqual}，避免靠响应时间逐字节猜密码。
     *
     * <p>格式损坏的哈希串一律返回 {@code false}，不抛异常 ——
     * 认证路径上任何异常都不该变成「放行」。
     *
     * @param plain  用户输入的明文密码
     * @param secret 配置里存的密码（哈希串或明文）
     * @return 是否匹配
     */
    public static boolean verify(String plain, String secret) {
        if (plain == null || secret == null) {
            return false;
        }
        if (!isHashed(secret)) {
            return MessageDigest.isEqual(
                    plain.getBytes(StandardCharsets.UTF_8),
                    secret.getBytes(StandardCharsets.UTF_8));
        }
        String[] parts = secret.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (iterations < 1) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] actual = pbkdf2(plain.toCharArray(), salt, iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // JDK 17 一定带 PBKDF2WithHmacSHA256，走到这里说明运行时被裁剪过
            throw new IllegalStateException("当前 JDK 不支持 " + ALGORITHM, e);
        } finally {
            spec.clearPassword();
        }
    }

    // ------------------------------------------------------------------
    // 命令行小工具：生成哈希串，省得使用者自己写代码
    // ------------------------------------------------------------------

    /**
     * 打印随机密码的哈希串，用于手工写配置。
     *
     * <pre>{@code java -cp harken-web.jar com.harlanking.harken.web.auth.PasswordHash 我的密码}</pre>
     *
     * @param args 第一个参数是明文密码
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("用法: PasswordHash <明文密码>");
            System.out.println("输出可直接粘贴到 harken.web.users[].password");
            return;
        }
        System.out.println(hash(args[0]));
    }
}
