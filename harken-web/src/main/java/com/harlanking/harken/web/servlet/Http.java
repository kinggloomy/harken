package com.harlanking.harken.web.servlet;

import com.harlanking.harken.web.json.Json;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 层的小工具集合：读体、写 JSON、取令牌、认来源。
 *
 * <h2>为什么错误响应的形状是固定的</h2>
 * <pre>{@code {"ok":false,"errorType":"HARKEN_UNAUTHORIZED","error":"..."}}</pre>
 * <p>与 {@link com.harlanking.harken.core.api.ScriptResult} 的字段对齐。
 * 调用方（尤其是 AI 客户端）只需要写一种解析逻辑：先看 HTTP 状态码判断「请求本身有没有问题」，
 * 再看 {@code ok} 判断「脚本有没有跑成」。
 *
 * <h2>安全头</h2>
 * <p>{@code Cache-Control: no-store} 与 {@code X-Content-Type-Options: nosniff} 一律带上。
 * 前者防止脚本正文被中间缓存留存，后者防止浏览器把 JSON 当 HTML 执行。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class Http {

    /** 会话令牌请求头。curl 用这个最方便，不依赖 Cookie 罐。 */
    public static final String SESSION_HEADER = "X-Harken-Session";

    /** 会话 Cookie 名。浏览器用。 */
    public static final String SESSION_COOKIE = "HARKEN_SESSION";

    private Http() {
    }

    // ------------------------------------------------------------------
    // 错误
    // ------------------------------------------------------------------

    /**
     * 可直接映射成 HTTP 响应的错误。
     *
     * <p>受检异常会让每个 servlet 方法都要 {@code throws}，而这里所有错误都是
     * 「渲染成响应」这一种归宿，所以用运行时异常 + 统一兜底。
     *
     * @author harlan
     * @since 1.0.0
     */
    public static final class Error extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int status;
        private final String code;

        /**
         * @param status  HTTP 状态码
         * @param code    机器可读错误码
         * @param message 面向使用者的中文说明
         */
        public Error(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        /**
         * @return HTTP 状态码
         */
        public int status() {
            return status;
        }

        /**
         * @return 机器可读错误码
         */
        public String code() {
            return code;
        }
    }

    /** @param msg 说明 @return 400 */
    public static Error badRequest(String msg) {
        return new Error(400, "HARKEN_BAD_REQUEST", msg);
    }

    /** @param code 错误码 @param msg 说明 @return 401 */
    public static Error unauthorized(String code, String msg) {
        return new Error(401, code, msg);
    }

    /** @param code 错误码 @param msg 说明 @return 403 */
    public static Error forbidden(String code, String msg) {
        return new Error(403, code, msg);
    }

    /** @param msg 说明 @return 404 */
    public static Error notFound(String msg) {
        return new Error(404, "HARKEN_NOT_FOUND", msg);
    }

    /** @param msg 说明 @return 405 */
    public static Error methodNotAllowed(String msg) {
        return new Error(405, "HARKEN_METHOD_NOT_ALLOWED", msg);
    }

    /** @param msg 说明 @return 413 */
    public static Error tooLarge(String msg) {
        return new Error(413, "HARKEN_BODY_TOO_LARGE", msg);
    }

    /** @param msg 说明 @return 429 */
    public static Error tooManyRequests(String msg) {
        return new Error(429, "HARKEN_LOGIN_LOCKED", msg);
    }

    // ------------------------------------------------------------------
    // 读请求体
    // ------------------------------------------------------------------

    /**
     * 读取请求体，超过上限立即中止。
     *
     * <p>先读 {@code maxBytes + 1} 个字节再判断，而不是信 {@code Content-Length}：
     * 后者可以被伪造或缺失（分块传输）。多读一个字节的成本远小于
     * 「声明 10 字节实际发了 1GB」的风险。
     *
     * @param req      请求
     * @param maxBytes 字节上限
     * @return UTF-8 文本
     * @throws Error 413 当超限
     * @throws IOException 读取失败
     */
    public static String readBody(HttpServletRequest req, int maxBytes) throws IOException {
        return new String(readBodyBytes(req, maxBytes), StandardCharsets.UTF_8);
    }

    /**
     * 读请求体原始字节。
     *
     * <p>签名校验必须基于<b>原始字节</b>而不是解码后的字符串：字符串解码会吃掉 BOM、
     * 替换非法字节，两边算出的摘要就可能不同。所以入口处只读一次字节，
     * 之后由调用方决定怎么解码 —— 也顺带保证了 {@code getInputStream()} 只被消费一次
     * （Servlet 的请求体流不可重读）。
     *
     * @param req      HTTP 请求
     * @param maxBytes 字节上限，超限抛 413
     * @return 原始字节
     * @throws IOException 读取失败
     */
    public static byte[] readBodyBytes(HttpServletRequest req, int maxBytes) throws IOException {
        try (InputStream in = req.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] chunk = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(chunk)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw tooLarge("请求体超过 " + maxBytes + " 字节上限");
                }
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        }
    }

    // ------------------------------------------------------------------
    // 写响应
    // ------------------------------------------------------------------

    /**
     * 写一个 JSON 响应。
     *
     * @param resp    响应
     * @param status  HTTP 状态码
     * @param payload 任意可被 {@link Json#write(Object)} 处理的对象
     * @throws IOException 写出失败
     */
    public static void json(HttpServletResponse resp, int status, Object payload) throws IOException {
        byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
        resp.getOutputStream().flush();
    }

    /**
     * 写错误响应，形状与控制台的 {@code ScriptResult} 对齐。
     *
     * @param resp  响应
     * @param error 错误
     * @throws IOException 写出失败
     */
    public static void error(HttpServletResponse resp, Error error) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", error.getMessage());
        body.put("errorType", error.code());
        json(resp, error.status(), body);
    }

    /**
     * 写一个成功但无内容的简单响应。
     *
     * @param resp 响应
     * @param body 键值对
     * @throws IOException 写出失败
     */
    public static void ok(HttpServletResponse resp, Map<String, Object> body) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.putAll(body);
        json(resp, 200, payload);
    }

    // ------------------------------------------------------------------
    // 令牌与来源
    // ------------------------------------------------------------------

    /**
     * 从请求里取会话令牌：优先请求头，其次 Cookie。
     *
     * @param req 请求
     * @return 令牌，没有则 {@code null}
     */
    public static String sessionToken(HttpServletRequest req) {
        String header = req.getHeader(SESSION_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (SESSION_COOKIE.equals(c.getName())) {
                    String v = c.getValue();
                    return v == null || v.isBlank() ? null : v;
                }
            }
        }
        return null;
    }

    /**
     * 提取 {@code Authorization: Bearer xxx} 里的令牌。
     *
     * @param req 请求
     * @return 令牌，缺失或格式不对则 {@code null}
     */
    public static String bearerToken(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.length() <= 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 判定来源地址。
     *
     * @param req          请求
     * @param trustXff     是否信任 {@code X-Forwarded-For}
     * @return 来源地址，取不到时返回 {@code ""}
     */
    public static String clientIp(HttpServletRequest req, boolean trustXff) {
        if (trustXff) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                String first = comma >= 0 ? xff.substring(0, comma) : xff;
                String v = first.trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        String remote = req.getRemoteAddr();
        return remote == null ? "" : remote;
    }

    /**
     * 判断这是不是一个跨站发起的浏览器请求（CSRF 防线）。
     *
     * <h2>为什么按 Host 比，而不是按 {@code getScheme()} 拼</h2>
     * <p>反代后面很常见：容器看到的是 {@code http://10.0.0.5:8080}，浏览器发的 Origin 是
     * {@code https://admin.example.com}。若用「scheme + serverName + serverPort」去拼，
     * 每一个正常的请求都会被判成跨站 —— 于是要么全线 403，要么有人把这道检查删了。
     * 比 {@link jakarta.servlet.http.HttpServletRequest#getHeader(String) Host} 头没有这个问题：
     * 它就是浏览器请求里那个 Host。
     *
     * <h2>没有 Origin 头算什么</h2>
     * <p>算「不是浏览器」。curl、SDK、服务端调用都不带 Origin，它们也没有<b>环境凭据</b>
     * （浏览器自动携带的 Cookie），构不成 CSRF。真正要防的是「别的站点让用户的浏览器发请求」，
     * 而浏览器一定会带 Origin。
     *
     * @param req             请求
     * @param trustForwardHost 是否采信 {@code X-Forwarded-Host}（仅当前面有自己的代理）
     * @return {@code true} 表示 Origin 与 Host 对不上，应当拒绝
     */
    public static boolean isCrossSite(HttpServletRequest req, boolean trustForwardHost) {
        String origin = req.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return false;
        }
        String reported = origin.trim();
        if ("null".equalsIgnoreCase(reported)) {
            // 沙箱 iframe、file:// 页面会发这个，来源不可知就是不安全
            return true;
        }
        int schemeEnd = reported.indexOf("://");
        String hostPart = schemeEnd >= 0 ? reported.substring(schemeEnd + 3) : reported;
        int slash = hostPart.indexOf('/');
        if (slash >= 0) {
            hostPart = hostPart.substring(0, slash);
        }
        String expected = req.getHeader("Host");
        if (trustForwardHost) {
            String forwarded = req.getHeader("X-Forwarded-Host");
            if (forwarded != null && !forwarded.isBlank()) {
                expected = forwarded.split(",", 2)[0].trim();
            }
        }
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return !hostPart.equalsIgnoreCase(expected.trim());
    }

    /**
     * 取 servlet 映射之后的路径部分，已去掉尾部斜杠。
     *
     * <p>例如映射 {@code /harken/api/*} 时，请求 {@code /harken/api/execute}
     * 返回 {@code /execute}。
     *
     * @param req 请求
     * @return 路径，可能是空串
     */
    public static String subPath(HttpServletRequest req) {
        String info = req.getPathInfo();
        if (info == null || info.isEmpty()) {
            return "";
        }
        if (info.length() > 1 && info.endsWith("/")) {
            return info.substring(0, info.length() - 1);
        }
        return info;
    }
}
