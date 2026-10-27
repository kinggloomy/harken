package com.harlanking.harken.web.servlet;

import com.harlanking.harken.core.HarkenException;
import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.api.ReadWriteRequest;
import com.harlanking.harken.core.api.ScriptResult;
import com.harlanking.harken.core.catalog.RiskLevel;
import com.harlanking.harken.core.catalog.ScriptDefinition;
import com.harlanking.harken.core.metadata.ClassIndex;
import com.harlanking.harken.core.metadata.MethodCatalog;
import com.harlanking.harken.web.HarkenWebConfig;
import com.harlanking.harken.web.Version;
import com.harlanking.harken.web.auth.AuthService;
import com.harlanking.harken.web.auth.SessionStore;
import com.harlanking.harken.web.json.Json;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人类通道，映射到 {@code {basePath}/api/*}。需要会话。
 *
 * <table border="1">
 *   <caption>端点</caption>
 *   <tr><th>方法与路径</th><th>说明</th></tr>
 *   <tr><td>{@code POST /execute}</td><td>执行脚本，{@code {"script":"...","commit":false}}</td></tr>
 *   <tr><td>{@code POST /compile}</td><td>只编译不执行，用于画语法红线</td></tr>
 *   <tr><td>{@code GET /metadata}</td><td>补全索引：类名（按包分组）+ Bean 名</td></tr>
 *   <tr><td>{@code GET /metadata?type=X}</td><td>X 的 {@code public static} 方法</td></tr>
 *   <tr><td>{@code GET /metadata?bean=X}</td><td>Bean X 的实例方法</td></tr>
 *   <tr><td>{@code GET /metadata?jdk=X}</td><td>JDK 包 X 下的类名</td></tr>
 *   <tr><td>{@code GET /audits?limit=N}</td><td>最近的审计记录，用于「最近执行」面板</td></tr>
 *   <tr><td>{@code GET /health}</td><td>事务/审计/守卫/AI 开关状态</td></tr>
 * </table>
 *
 * <h2>为什么 {@code commit} 的权限判断放在这里，而不是让服务层拒绝</h2>
 * <p>服务层不知道自己面对的是哪个角色 —— 它不该知道。角色是 HTTP 层带来的上下文，
 * 所以「READONLY 不许 commit」在这一层判。但服务层仍然独立地检查「有没有事务管理器」，
 * 两层检查互不替代。
 *
 * <p><b>注意 READONLY 角色不会被静默降级成 {@code commit=false}</b>：那会让用户以为
 * 自己提交了，实际没有。明确返回 403，让他知道去找谁要权限。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class HumanApiServlet extends HarkenServlet {

    private static final long serialVersionUID = 1L;

    /** {@code /audits} 不给 limit 时返回多少条。20 条正好填满一屏面板，不必滚动。 */
    public static final int DEFAULT_AUDIT_LIMIT = 20;

    /** {@code /audits} 的硬上限。 */
    public static final int MAX_AUDIT_LIMIT = 200;

    private final HarkenService service;
    private final ClassIndex classIndex;
    private final MethodCatalog catalog;
    private final AuthService auth;
    private final HarkenWebConfig config;

    /**
     * @param service    执行编排器
     * @param classIndex 业务类名索引，不能为 {@code null}
     * @param catalog    方法表，不能为 {@code null}
     * @param auth       认证中心
     * @param config     HTTP 配置
     */
    public HumanApiServlet(HarkenService service, ClassIndex classIndex, MethodCatalog catalog,
                           AuthService auth, HarkenWebConfig config) {
        this.service = service;
        this.classIndex = classIndex;
        this.catalog = catalog;
        this.auth = auth;
        this.config = config;
    }

    @Override
    protected void handle(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String path = Http.subPath(req);
        String method = req.getMethod();
        switch (path) {
            case "/execute" -> {
                requireMethod(method, "POST", path);
                execute(req, resp);
            }
            case "/catalog" -> {
                requireMethod(method, "GET", path);
                catalog(req, resp);
            }
            case "/compile" -> {
                requireMethod(method, "POST", path);
                compile(req, resp);
            }
            case "/metadata" -> {
                requireMethod(method, "GET", path);
                metadata(req, resp);
            }
            case "/audits" -> {
                requireMethod(method, "GET", path);
                audits(req, resp);
            }
            case "/health" -> {
                requireMethod(method, "GET", path);
                health(req, resp);
            }
            default -> throw Http.notFound("未知的控制台端点：" + path);
        }
    }

    private static void requireMethod(String actual, String expected, String path) {
        if (!expected.equalsIgnoreCase(actual)) {
            throw Http.methodNotAllowed(path + " 只接受 " + expected);
        }
    }

    // ------------------------------------------------------------------

    private void execute(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionStore.Session session = requireSession(req);
        // 浏览器里能写数据库的端点，多一道同源校验。Cookie 已经是 SameSite=Strict，
        // 这道检查是第二条命：万一哪天有人为了「嵌进内部系统」把 SameSite 改成 None，
        // 这里仍然拒绝外站发起的提交。
        if (Http.isCrossSite(req, config.trustForwardedFor())) {
            throw Http.forbidden("HARKEN_CROSS_ORIGIN",
                    "拒绝执行：请求的 Origin 与本服务不一致。控制台只能在同源页面里发起执行。");
        }
        Map<String, Object> body = Json.parseObject(Http.readBody(req, config.maxBodyBytes()));

        boolean restricted = config.restricted();
        String script;
        Map<String, Object> params = Map.of();
        boolean commitAllowedByDef = true;

        if (restricted) {
            // 受限模式：执行入口必须是「目录里的一个菜单项」，不允许任意脚本正文。
            // 这一步把「用户能喂什么」从「一段 Groovy 文本」锁成「一个 scriptId」。
            String scriptId = asString(body.get("scriptId"));
            if (scriptId == null || scriptId.isEmpty()) {
                throw Http.badRequest("受限模式下必须提供 scriptId（不允许自由编写脚本）");
            }
            ScriptDefinition def = service.catalog().find(scriptId)
                    .orElseThrow(() -> Http.notFound("未找到脚本：" + scriptId));
            // 兜底再拦一次危险等级：列表接口已经按 maxRisk 过滤过，这里防止有人
            // 直接拿一个被过滤掉的 scriptId 来撞。
            if (def.risk().ordinal() > config.maxRiskForRestricted().ordinal()) {
                throw Http.forbidden("HARKEN_RISK_EXCEEDED",
                        "脚本「" + def.name() + "」的危险等级 " + def.risk()
                                + " 超过受限模式允许上限 " + config.maxRiskForRestricted());
            }
            script = def.script();
            commitAllowedByDef = def.commitAllowed();
            List<String> missing = def.missingRequired(asMap(body.get("params")));
            if (!missing.isEmpty()) {
                throw Http.badRequest("缺少必填参数：" + String.join(", ", missing));
            }
            params = def.bind(asMap(body.get("params")));
        } else {
            script = asString(body.get("script"));
            if (script == null || script.isEmpty()) {
                throw Http.badRequest("缺少 script 字段");
            }
        }

        boolean commit = asBoolean(body.get("commit"), false);
        if (restricted && commit && !commitAllowedByDef) {
            throw Http.forbidden("HARKEN_FORBIDDEN_COMMIT",
                    "该脚本不允许提交事务（commitAllowed=false），只能试跑查看影响。");
        }
        if (commit && !session.role().canCommit()) {
            throw Http.forbidden("HARKEN_FORBIDDEN_COMMIT",
                    "当前角色 " + session.role() + " 不允许提交事务。"
                            + "可以用 commit=false 试跑查看影响，或联系管理员提升权限。");
        }

        String clientIp = Http.clientIp(req, config.trustForwardedFor());
        ScriptResult result = service.execute(new ReadWriteRequest(script, commit, params),
                session.username(), clientIp);
        Http.json(resp, 200, ScriptResultJson.toMap(result));
    }

    private void catalog(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        requireSession(req);
        boolean restricted = config.restricted();
        List<ScriptDefinition> all = service.catalog().list();
        List<ScriptDefinition> visible = all;
        if (restricted) {
            // 受限模式：只暴露不超过 maxRisk 的脚本，且<b>不下发脚本源码</b>，
            // 否则用户把源码复制下来就等于绕过了「不能写代码」的限制。
            int max = config.maxRiskForRestricted().ordinal();
            visible = all.stream().filter(d -> d.risk().ordinal() <= max).toList();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", config.mode().name());
        payload.put("restricted", restricted);
        payload.put("maxRisk", config.maxRiskForRestricted().name());
        payload.put("scripts", CatalogJson.toList(visible, !restricted));
        Http.ok(resp, payload);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Map.of();
    }

    private void compile(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        requireSession(req);
        Map<String, Object> body = Json.parseObject(Http.readBody(req, config.maxBodyBytes()));
        String script = asString(body.get("script"));
        if (script == null || script.isEmpty()) {
            throw Http.badRequest("缺少 script 字段");
        }
        try {
            service.compileOnly(script);
            Http.ok(resp, Map.of());
        } catch (HarkenException.Compilation e) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", false);
            payload.put("errorType", "COMPILATION");
            payload.put("error", e.getMessage());
            payload.put("line", e.line());
            payload.put("column", e.column());
            Http.json(resp, 400, payload);
        }
    }

    private void metadata(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionStore.Session session = requireSession(req);
        Map<String, Object> payload = MetadataJson.view(classIndex, catalog,
                req.getParameter("type"), req.getParameter("inst"),
                req.getParameter("bean"), req.getParameter("jdk"));
        payload.put("transactionSupported", service.transactionSupported());
        payload.put("canCommit", session.role().canCommit());
        Http.json(resp, 200, payload);
    }

    private void audits(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        requireSession(req);
        int limit = parseLimit(req.getParameter("limit"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("queryable", service.auditQueryable());
        payload.put("limit", limit);
        payload.put("records", AuditJson.toList(service.recentAudits(limit)));
        Http.ok(resp, payload);
    }

    /**
     * 解析 {@code limit}。
     *
     * <p>上限 {@value #MAX_AUDIT_LIMIT} 是硬顶而不是「建议值」：审计记录里带脚本预览，
     * 一次要 10 万条就是一次自伤式的内存放大。
     */
    private static int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_AUDIT_LIMIT;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            if (n < 1) {
                return 1;
            }
            return Math.min(n, MAX_AUDIT_LIMIT);
        } catch (NumberFormatException e) {
            throw Http.badRequest("limit 必须是整数，收到 " + raw);
        }
    }

    private void health(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionStore.Session session = requireSession(req);
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("supported", service.transactionSupported());
        tx.put("description", service.transactionDescription());

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("stores", service.auditDescription());
        audit.put("queryable", service.auditQueryable());

        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("enabled", auth.aiEnabled());

        // 写入守卫描述原样透出，前端不解析。策略格式将来会加东西（比如按表设不同阈值），
        // 让前端认识这个字符串，就等于把「服务端策略」复制了一份到浏览器里，
        // 两份格式一旦不同步，状态条开始撒谎
        Map<String, Object> guard = new LinkedHashMap<>();
        guard.put("description", service.writeGuardDescription());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", Version.get());
        payload.put("environment", config.environment());
        payload.put("mode", config.mode().name());
        payload.put("restricted", config.restricted());
        payload.put("transaction", tx);
        payload.put("audit", audit);
        payload.put("writeGuard", guard);
        payload.put("ai", ai);
        payload.put("sessions", auth.activeSessionCount());
        payload.put("users", auth.userCount());
        payload.put("username", session.username());
        payload.put("role", session.role().name());
        payload.put("canCommit", session.role().canCommit());
        payload.put("timeoutMs", service.options().timeoutMs());
        payload.put("maxScriptLength", service.options().maxScriptLength());
        Http.ok(resp, payload);
    }

    private SessionStore.Session requireSession(HttpServletRequest req) {
        String token = Http.sessionToken(req);
        SessionStore.Session session = auth.touch(token);
        if (session == null) {
            throw Http.unauthorized("HARKEN_UNAUTHORIZED", "未登录或会话已失效，请先 POST "
                    + config.basePath() + "/auth/login");
        }
        return session;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean asBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw Http.badRequest("commit 必须是布尔值，收到 " + Json.write(value));
    }
}
