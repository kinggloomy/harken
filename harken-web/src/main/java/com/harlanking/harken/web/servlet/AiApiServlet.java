package com.harlanking.harken.web.servlet;

import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.api.ReadOnlyRequest;
import com.harlanking.harken.core.api.ScriptResult;
import com.harlanking.harken.core.metadata.ClassIndex;
import com.harlanking.harken.core.metadata.MethodCatalog;
import com.harlanking.harken.web.HarkenWebConfig;
import com.harlanking.harken.web.Version;
import com.harlanking.harken.web.auth.AuthService;
import com.harlanking.harken.web.auth.SignatureVerifier;
import com.harlanking.harken.web.json.Json;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * AI 通道，映射到 {@code {basePath}/ai/*}。与人类通道<b>物理隔离</b>。
 *
 * <table border="1">
 *   <caption>端点</caption>
 *   <tr><th>方法与路径</th><th>说明</th></tr>
 *   <tr><td>{@code POST /query}</td><td>执行一段<b>只读</b>脚本，{@code {"script":"..."}}</td></tr>
 *   <tr><td>{@code GET /capabilities}</td><td>补全索引（gzip），给 Agent 自述能力用</td></tr>
 *   <tr><td>{@code GET /capabilities?bean=X}</td><td>Bean X 的实例方法（gzip）</td></tr>
 *   <tr><td>{@code GET /capabilities?type=X}</td><td>类 X 的静态方法（gzip）</td></tr>
 *   <tr><td>{@code GET /capabilities?jdk=X}</td><td>JDK 包 X 的类名（gzip）</td></tr>
 * </table>
 *
 * <h2>「物理隔离」具体隔离了什么</h2>
 * <ol>
 *   <li><b>独立类</b>：不是同一个 servlet 里 {@code if (isAi)}，而是两个类。</li>
 *   <li><b>独立凭据</b>：Bearer Token，与人类会话表完全无关。AI 的 Token 丢了不会波及人类会话。</li>
 *   <li><b>独立类型</b>：这里只构造 {@link ReadOnlyRequest}。{@code ReadWriteRequest} 在本类里
 *       一次都没出现，因此「AI 写库」在<b>编译期</b>就不可能 —— 不依赖任何运行时判断。</li>
 *   <li><b>显式拒绝 {@code commit}</b>：请求体里出现 {@code commit} 直接 400。
 *       静默忽略会让人以为「我传了 commit 但它没生效」，而实际是设计上不允许。</li>
 * </ol>
 *
 * <h2>没配 Token 时这个 servlet 根本不会被注册</h2>
 * <p>不存在「Token 为空就跳过校验」的分支。端点不存在，比端点存在但校验有 bug 安全得多。
 *
 * <h2>第二道门：请求签名（SR-04）</h2>
 * <p>配了 {@code harken.ai.signing-secret} 后，除 Token 外还要求三件事同时成立：
 * 时间窗内（默认 60 秒）、签名正确（HMAC-SHA256）、nonce 第一次使用。
 * 这一层挡的是<b>重放</b>：Token 本身是长期凭据，被抓到就长期有效；
 * 而签名绑定了「这一次请求的方法、路径、时间、body 摘要」。
 * <p>未配密钥时不要求签名 —— 与「没配 Token 就不注册」不同，这是一个<b>有意的降级</b>：
 * 签名需要调用方配合改代码，不是所有人都需要，所以默认只靠 Token；但一旦配了密钥，
 * 就<b>强制</b>所有请求都必须签，没有「配了但可选」的中间态。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class AiApiServlet extends HarkenServlet {

    private static final long serialVersionUID = 1L;

    /** 签名头名。做成常量是为了让「文档、脚本、服务端」三处不会各写错一个字母。 */
    public static final String HDR_TIMESTAMP = "X-Harken-Timestamp";
    /** 见 {@link #HDR_TIMESTAMP}。 */
    public static final String HDR_NONCE = "X-Harken-Nonce";
    /** 见 {@link #HDR_TIMESTAMP}。 */
    public static final String HDR_SIGNATURE = "X-Harken-Signature";

    private final HarkenService service;
    private final ClassIndex classIndex;
    private final MethodCatalog catalog;
    private final AuthService auth;
    private final HarkenWebConfig config;

    /**
     * @param service    执行编排器
     * @param classIndex 业务类名索引，不能为 {@code null}
     * @param catalog    方法表，不能为 {@code null}
     * @param auth       认证中心（只用来校验 AI Token）
     * @param config     HTTP 配置
     */
    public AiApiServlet(HarkenService service, ClassIndex classIndex, MethodCatalog catalog,
                        AuthService auth, HarkenWebConfig config) {
        this.service = service;
        this.classIndex = classIndex;
        this.catalog = catalog;
        this.auth = auth;
        this.config = config;
    }

    @Override
    protected void handle(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        // 先认证再路由：连「这个路径存不存在」都不告诉未授权者
        requireToken(req);

        String path = Http.subPath(req);
        switch (path) {
            case "/query" -> {
                requirePost(req, path);
                // 先读字节再验签：签名覆盖 body 摘要，必须拿到原始字节（且只能读一次）
                byte[] body = Http.readBodyBytes(req, config.maxBodyBytes());
                requireSignature(req, body);
                query(req, resp, body);
            }
            case "/capabilities" -> {
                requireGet(req, path);
                requireSignature(req, new byte[0]);
                capabilities(req, resp);
            }
            default -> throw Http.notFound("未知的 AI 端点：" + path
                    + "（可用：/query、/capabilities）");
        }
    }

    /**
     * 校验请求签名（SR-04）。未配置密钥时直接放行。
     *
     * <p>错误码拆成四个而不是笼统一个 401，是因为排查时最有价值的信息就是
     * 「到底哪一步没过」：时钟没同步（STALE）和 nonce 重复（REPLAY）是两类完全不同的事故。
     */
    private void requireSignature(HttpServletRequest req, byte[] body) {
        SignatureVerifier verifier = auth.signatureVerifier();
        if (!verifier.enabled()) {
            return;
        }
        SignatureVerifier.Verdict verdict = verifier.verify(
                req.getMethod(), req.getRequestURI(),
                req.getHeader(HDR_TIMESTAMP), req.getHeader(HDR_NONCE), req.getHeader(HDR_SIGNATURE),
                body, System.currentTimeMillis());
        if (verdict.ok()) {
            return;
        }
        throw switch (verdict.status()) {
            case REPLAY -> Http.unauthorized("HARKEN_SIGNATURE_REPLAY", verdict.message());
            case EXPIRED, BAD_TIMESTAMP -> Http.unauthorized("HARKEN_SIGNATURE_STALE", verdict.message());
            case BAD_SIGNATURE -> Http.unauthorized("HARKEN_SIGNATURE_INVALID", verdict.message());
            default -> Http.unauthorized("HARKEN_SIGNATURE_REQUIRED", verdict.message());
        };
    }

    private void requireToken(HttpServletRequest req) {
        String presented = Http.bearerToken(req);
        if (presented == null) {
            throw Http.unauthorized("HARKEN_AI_UNAUTHORIZED",
                    "缺少 Authorization: Bearer <token> 请求头。");
        }
        if (!auth.checkAiToken(presented)) {
            throw Http.unauthorized("HARKEN_AI_UNAUTHORIZED", "AI Token 无效。");
        }
    }

    private static void requirePost(HttpServletRequest req, String path) {
        if (!"POST".equalsIgnoreCase(req.getMethod())) {
            throw Http.methodNotAllowed(path + " 只接受 POST");
        }
    }

    private static void requireGet(HttpServletRequest req, String path) {
        if (!"GET".equalsIgnoreCase(req.getMethod())) {
            throw Http.methodNotAllowed(path + " 只接受 GET");
        }
    }

    // ------------------------------------------------------------------

    private void query(HttpServletRequest req, HttpServletResponse resp, byte[] rawBody) throws IOException {
        Map<String, Object> body = Json.parseObject(new String(rawBody, StandardCharsets.UTF_8));

        if (body.containsKey("commit")) {
            // 不静默忽略：让调用方立刻知道自己用错了通道
            throw Http.badRequest("AI 通道不接受 commit 参数。本通道的请求类型里没有这个概念，"
                    + "脚本一律在只读事务中执行并回滚。需要写数据请走人类通道。");
        }

        String script = asString(body.get("script"));
        if (script == null || script.isEmpty()) {
            throw Http.badRequest("缺少 script 字段");
        }

        String clientIp = Http.clientIp(req, config.trustForwardedFor());
        // 唯一的构造点：ReadOnlyRequest。operator 固定为 "ai"，让审计能一眼区分来源
        ScriptResult result = service.execute(new ReadOnlyRequest(script), "ai", clientIp);
        Http.json(resp, 200, ScriptResultJson.toMap(result));
    }

    private void capabilities(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> payload = MetadataJson.view(classIndex, catalog,
                req.getParameter("type"), req.getParameter("inst"),
                req.getParameter("bean"), req.getParameter("jdk"));

        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("channel", "ai");
        ai.put("readOnly", true);
        ai.put("version", Version.get());
        ai.put("timeoutMs", service.options().timeoutMs());
        ai.put("maxScriptLength", service.options().maxScriptLength());
        ai.put("resultMaxBytes", service.options().resultMaxBytes());
        ai.put("outputMaxBytes", service.options().outputMaxBytes());
        ai.put("note", "脚本只能读取数据。写操作会在数据库层被拒绝，"
                + "或被执行后回滚。返回值超过上限会被截断并给出 hint。"
                + "不带参数的 /capabilities 只给索引，用 ?bean= 或 ?type= 查具体方法。");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ok", true);
        root.put("ai", ai);
        root.putAll(payload);

        byte[] bytes = Json.write(root).getBytes(StandardCharsets.UTF_8);
        // 索引动辄几十 KB，gzip 能压掉 80% 以上，对 Agent 的上下文成本是实打实的节省
        boolean gzip = acceptsGzip(req) && bytes.length > 1024;
        resp.setStatus(200);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        if (gzip) {
            resp.setHeader("Content-Encoding", "gzip");
            resp.setHeader("Vary", "Accept-Encoding");
            try (GZIPOutputStream out = new GZIPOutputStream(resp.getOutputStream())) {
                out.write(bytes);
            }
        } else {
            resp.setContentLength(bytes.length);
            resp.getOutputStream().write(bytes);
        }
        resp.getOutputStream().flush();
    }

    private static boolean acceptsGzip(HttpServletRequest req) {
        String header = req.getHeader("Accept-Encoding");
        return header != null && header.toLowerCase(Locale.ROOT).contains("gzip");
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
