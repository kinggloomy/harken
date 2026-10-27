package com.harlanking.harken.web.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 请求签名：把「一次请求」压成一个 HMAC-SHA256，用来防重放与防篡改（SR-04）。
 *
 * <h2>为什么要给一个 Bearer Token 再加一层签名</h2>
 * <p>Token 是「长期凭据」，一旦出现在日志、代理缓存、异常栈、截图里就长期有效，
 * 而签名是「一次一签」，还绑定了时间窗与一次性 nonce。威胁模型很具体：
 * <ol>
 *   <li><b>重放</b>：抓到一次 {@code POST /harken/ai/query}（内容是 {@code 把 3 万条订单标为已支付}），
 *       再发一次。裸 Token 拦不住，签名 + nonce 拦得住。</li>
 *   <li><b>篡改</b>：签名覆盖了 body 摘要，改任何一个字符都会验签失败。</li>
 *   <li><b>旧请求</b>：时间窗外的请求直接拒绝，不必依赖 nonce 表无限增长。</li>
 * </ol>
 *
 * <h2>签名串为什么长这样</h2>
 * <pre>
 * METHOD \n PATH \n TIMESTAMP \n NONCE \n HEX(SHA256(body))
 * </pre>
 * <ul>
 *   <li><b>METHOD + PATH</b>：把签名绑定到具体端点。否则一个 {@code /capabilities} 的签名
 *       可以被挪去调用 {@code /query}（同样的空 body、同样的时间戳）。</li>
 *   <li><b>body 摘要而不是 body 本身</b>：body 可能几百 KB，先摘要再参与 HMAC，
 *       两边计算量都稳定；摘要已经足够防篡改（碰撞不可行）。</li>
 *   <li><b>固定用 {@code \n} 分隔、不拼接</b>：拼接会产生歧义 ——
 *       {@code ("ab","c")} 与 {@code ("a","bc")} 会得到同一个待签串。用不可出现于字段内的
 *       换行分隔是这类协议的标准做法。</li>
 * </ul>
 *
 * <p><b>不覆盖 Header 里的其它字段</b>（如 {@code Content-Type}）：多覆盖一项就多一个
 * 「客户端与服务端对不齐」的机会，而 Harken 的请求体只认 UTF-8 JSON，多覆盖没有收益。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class RequestSigner {

    /** 算法名。写死，不接受配置 —— 允许退化到 MD5 之类的配置项只会被用错。 */
    private static final String ALGORITHM = "HmacSHA256";

    private RequestSigner() {
    }

    /**
     * 生成待签名字符串。
     *
     * @param method     HTTP 方法，大小写不敏感（内部统一大写）
     * @param path       请求路径，建议用 {@code getRequestURI()}（不含查询串）
     * @param timestamp  时间戳，秒级 epoch
     * @param nonce      一次性随机串
     * @param body       请求体原始字节；GET 等无体请求传空数组
     * @return 待签名串
     */
    public static String canonical(String method, String path, String timestamp, String nonce, byte[] body) {
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String p = path == null ? "" : path;
        String ts = timestamp == null ? "" : timestamp.trim();
        String n = nonce == null ? "" : nonce.trim();
        return m + "\n" + p + "\n" + ts + "\n" + n + "\n" + sha256Hex(body);
    }

    /**
     * 计算签名。
     *
     * @param secret    共享密钥（服务端与调用方各存一份）
     * @param method    HTTP 方法
     * @param path      请求路径
     * @param timestamp 秒级 epoch
     * @param nonce     一次性随机串
     * @param body      请求体
     * @return 小写十六进制签名
     */
    public static String sign(String secret, String method, String path,
                              String timestamp, String nonce, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(canonical(method, path, timestamp, nonce, body)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            // HmacSHA256 是 JDK 必备算法；走到这里说明运行环境被人动过，不该静默降级
            throw new IllegalStateException("当前 JDK 不支持 " + ALGORITHM, e);
        }
    }

    /**
     * 校验签名。
     *
     * <p>用 {@link MessageDigest#isEqual} 做<b>常数时间</b>比较：普通的
     * {@code String.equals} 在第一个不同字节就返回，攻击者可以通过响应时间差
     * 逐字节猜出正确签名（时序侧信道）。这里比较的是固定长度的十六进制串，成本可以忽略。
     *
     * @param secret    共享密钥
     * @param method    HTTP 方法
     * @param path      请求路径
     * @param timestamp 秒级 epoch
     * @param nonce     一次性随机串
     * @param body      请求体
     * @param presented 调用方给出的签名（大小写不敏感）
     * @return {@code true} 表示签名正确
     */
    public static boolean verify(String secret, String method, String path,
                                 String timestamp, String nonce, byte[] body, String presented) {
        if (presented == null) {
            return false;
        }
        String expected = sign(secret, method, path, timestamp, nonce, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param body 请求体；{@code null} 视为空
     * @return SHA-256 的小写十六进制串
     */
    public static String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body == null ? new byte[0] : body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
