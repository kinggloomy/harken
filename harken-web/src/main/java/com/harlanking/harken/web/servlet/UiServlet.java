package com.harlanking.harken.web.servlet;

import jakarta.servlet.http.HttpServletRequest;import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

/**
 * 控制台页面，映射到 {@code {basePath}/*}。资源全部来自本模块 jar 内的
 * {@code harken/web/} 目录。
 *
 * <h2>为什么不放在 {@code src/main/resources/static}</h2>
 * <p>那是 Spring Boot 的约定。放在那里意味着这个模块在没有 Spring 的应用里
 * 就<b>没有界面</b>，而「任意 Java Web 应用加个 jar 就有控制台」正是本项目的立身之本。
 * 自己用 {@code getResourceAsStream} 读 classpath，任何容器都一样。
 *
 * <h2>为什么资源要自己 gzip</h2>
 * <p>CodeMirror 未压缩约 400KB。不压缩等于每次首屏都要拉半个 MB —— 在只有 SSH 隧道
 * 能连的生产机上，这个差别是「能等」和「不能等」。压缩结果按资源缓存一份，
 * 每个资源在进程生命周期内只压一次。
 *
 * <h2>安全头的取舍</h2>
 * <ul>
 *   <li><b>CSP {@code default-src 'none'}</b>：页面只需要自己的 js/css 和同源 XHR，
 *       其余一律关掉。控制台能提交事务，被注入一段脚本就等于把库交给对方。</li>
 *   <li><b>{@code frame-ancestors 'none'} + {@code X-Frame-Options: DENY}</b>：
 *       防点击劫持。控制台右上角就是「提交」按钮，套个透明 iframe 就能骗人点。</li>
 *   <li><b>index.html 用 {@code no-store}</b>：升级 Harken 之后旧页面引用着已被替换的
 *       资源名，缓存住 index 会让人看到「白屏 + 一堆 404」。资源本身用
 *       {@code no-cache} + ETag，重新验证通常只有 304，成本很低。</li>
 * </ul>
 *
 * @author harlan
 * @since 1.0.0
 */
public final class UiServlet extends HarkenServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(UiServlet.class);

    /** classpath 下的资源根。 */
    public static final String RESOURCE_ROOT = "harken/web/";

    /** 静态资源路径前缀。 */
    public static final String ASSET_PREFIX = "/assets/";

    /**
     * 内容安全策略。
     *
     * <p>没有 {@code 'unsafe-inline'}，也没有 {@code 'unsafe-eval'} ——
     * CodeMirror 5 这两样都不需要（已核对：无 {@code eval} / {@code new Function} /
     * {@code setAttribute("style")} / {@code innerHTML}）。这条注释就是将来有人
     * 想放开时的第一道提醒。
     */
    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private final Map<String, Asset> cache = new ConcurrentHashMap<>();

    /** 构造器。 */
    public UiServlet() {
    }

    @Override
    protected void handle(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String method = req.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            throw Http.methodNotAllowed("控制台页面只接受 GET");
        }
        String path = Http.subPath(req);
        String name;
        if (path.isEmpty()) {
            // 请求的是 /harken 而不是 /harken/。页面里的资源是相对路径（assets/app.js），
            // 基准 URL 会把末段当文件名丢掉，于是浏览器去拉 /assets/app.js → 404 → 白屏。
            // CSP 里有 base-uri 'none'，<base> 修不了，只能在这里先跳到带斜杠的地址。
            String query = req.getQueryString();
            resp.sendRedirect(req.getRequestURI() + "/"
                    + (query == null || query.isEmpty() ? "" : "?" + query));
            return;
        }
        if ("/".equals(path) || "/index.html".equals(path)) {
            name = "index.html";
        } else if (path.startsWith(ASSET_PREFIX)) {
            name = path.substring(ASSET_PREFIX.length());
        } else {
            throw Http.notFound("控制台没有这个文件：" + path);
        }
        if (!isSafeName(name)) {
            // 路径穿越的第一道门。第二道是「资源必须在 harken/web/ 之下」这条构造方式本身：
            // 没有文件系统参与，也就没有 ../.. 能爬到的地方
            throw Http.notFound("非法的资源名");
        }
        Asset asset = load(name);
        if (asset == null) {
            throw Http.notFound("控制台资源不存在：" + name);
        }
        write(req, resp, asset, "HEAD".equalsIgnoreCase(method));
    }

    /**
     * 资源名白名单：只允许字母、数字、点、下划线、短横线、斜杠。
     *
     * <p>其中 {@code ..} 单独禁掉：{@code ./../../etc/passwd} 每个字符都合规。
     */
    static boolean isSafeName(String name) {
        if (name == null || name.isEmpty() || name.length() > 200) {
            return false;
        }
        if (name.contains("..") || name.startsWith("/") || name.indexOf('\\') >= 0) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private void write(HttpServletRequest req, HttpServletResponse resp, Asset asset, boolean head)
            throws IOException {
        resp.setHeader("ETag", asset.etag());
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("X-Frame-Options", "DENY");
        resp.setHeader("Referrer-Policy", "no-referrer");
        if (asset.isHtml()) {
            resp.setHeader("Content-Security-Policy", CSP);
            resp.setHeader("Cache-Control", "no-store");
        } else {
            resp.setHeader("Cache-Control", "no-cache");
        }

        String ifNoneMatch = req.getHeader("If-None-Match");
        if (ifNoneMatch != null && ifNoneMatch.contains(asset.etag())) {
            resp.setStatus(304);
            return;
        }

        boolean gzip = asset.gzip() != null && acceptsGzip(req);
        byte[] body = gzip ? asset.gzip() : asset.raw();
        resp.setStatus(200);
        resp.setContentType(asset.contentType());
        resp.setCharacterEncoding("UTF-8");
        if (gzip) {
            resp.setHeader("Content-Encoding", "gzip");
            // 中间缓存必须知道「同一个 URL 有两种表示」，否则可能把 gzip 版本发给不认 gzip 的客户端
            resp.setHeader("Vary", "Accept-Encoding");
        }
        resp.setContentLength(body.length);
        if (head) {
            return;
        }
        resp.getOutputStream().write(body);
        resp.getOutputStream().flush();
    }

    private static boolean acceptsGzip(HttpServletRequest req) {
        String header = req.getHeader("Accept-Encoding");
        return header != null && header.toLowerCase(Locale.ROOT).contains("gzip");
    }

    private Asset load(String name) {
        Asset cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        String text = read(name);
        if (text == null) {
            return null;
        }
        Asset asset = new Asset(name, text, contentType(name), gzipIfWorthIt(text));
        cache.put(name, asset);
        log.debug("Harken 控制台资源已载入：{}（{} 字节）", name, text.length());
        return asset;
    }

    private String read(String name) {
        try (InputStream in = UiServlet.class.getClassLoader()
                .getResourceAsStream(RESOURCE_ROOT + name)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Harken 控制台资源读取失败：{}", name, e);
            return null;
        }
    }

    /**
     * 小于 1KB 的内容不压 —— gzip 头 18 字节，压完往往比原文还大。
     */
    private static byte[] gzipIfWorthIt(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 1024) {
            return null;
        }
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(raw.length / 3);
            try (GZIPOutputStream out = new GZIPOutputStream(buf)) {
                out.write(raw);
            }
            return buf.toByteArray();
        } catch (IOException e) {
            // 压缩失败就用原文，不值得因为省几十 KB 让页面加载不出来
            return null;
        }
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) {
            return "text/html;charset=UTF-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css;charset=UTF-8";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript;charset=UTF-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        return "text/plain;charset=UTF-8";
    }

    /**
     * 一个已载入内存的资源。raw 与 gzip 都存着：压过的字节不能反解成原文，
     * 而 304 判定要用原文算 ETag。
     *
     * @param name        资源名
     * @param raw         原始字节
     * @param contentType MIME
     * @param gzip        gzip 后的字节，{@code null} 表示不值得压
     */
    private record Asset(String name, byte[] raw, String contentType, byte[] gzip) {

        Asset(String name, String text, String contentType, byte[] gzip) {
            this(name, text.getBytes(StandardCharsets.UTF_8), contentType, gzip);
        }

        String etag() {
            // 用内容算而不是用 mtime：同一个 jar 出来的字节永远一样，
            // 重启换了 jar 但内容没变时也能命中 304
            return "\"" + sha256(raw) + "\"";
        }

        boolean isHtml() {
            return contentType.startsWith("text/html");
        }

        private static String sha256(byte[] bytes) {
            try {
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
                StringBuilder sb = new StringBuilder(24);
                // ETag 不需要 64 位十六进制：取前 12 字节（96 bit）碰撞概率已经可以忽略，
                // 而头部短 40 个字符，几百个资源就是几十 KB 的请求头
                for (int i = 0; i < 12; i++) {
                    sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                    sb.append(Character.forDigit(hash[i] & 0xF, 16));
                }
                return sb.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 不可用", e);
            }
        }
    }
}
