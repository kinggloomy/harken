package com.harlanking.harken.web.servlet;

import com.harlanking.harken.web.HarkenWebConfig;
import com.harlanking.harken.web.auth.AuthService;
import com.harlanking.harken.web.auth.SessionStore;
import com.harlanking.harken.web.json.Json;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证端点，映射到 {@code {basePath}/auth/*}。
 *
 * <table border="1">
 *   <caption>端点</caption>
 *   <tr><th>方法与路径</th><th>说明</th><th>权限</th></tr>
 *   <tr><td>{@code POST /login}</td><td>用户名密码换会话令牌</td><td>任何人（受 IP 白名单约束）</td></tr>
 *   <tr><td>{@code POST /logout}</td><td>注销当前会话</td><td>有效会话</td></tr>
 *   <tr><td>{@code GET /me}</td><td>查看当前身份</td><td>有效会话</td></tr>
 *   <tr><td>{@code POST /reload}</td><td>重新解析 {@code env:} 凭据并踢掉所有会话</td><td>ADMIN</td></tr>
 * </table>
 *
 * <h2>登录响应同时给两种凭据</h2>
 * <p>JSON 里给 {@code token}（curl / AI 客户端用），同时下发 {@code HttpOnly} Cookie
 * （浏览器用，避免把令牌放进 localStorage 被 XSS 偷走）。
 * 两条路都通，但服务端只认一个令牌，不存在「两套认证」的复杂度。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class AuthServlet extends HarkenServlet {

    private static final long serialVersionUID = 1L;

    private final AuthService auth;
    private final HarkenWebConfig config;

    /**
     * @param auth   认证中心
     * @param config HTTP 配置
     */
    public AuthServlet(AuthService auth, HarkenWebConfig config) {
        this.auth = auth;
        this.config = config;
    }

    @Override
    protected void handle(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String path = Http.subPath(req);
        String method = req.getMethod();
        switch (path) {
            case "/login" -> {
                requirePost(method, path);
                login(req, resp);
            }
            case "/logout" -> {
                requirePost(method, path);
                logout(req, resp);
            }
            case "/reload" -> {
                requirePost(method, path);
                reload(req, resp);
            }
            case "/me" -> {
                requireGet(method, path);
                me(req, resp);
            }
            default -> throw Http.notFound("未知的认证端点：" + path);
        }
    }

    private static void requirePost(String method, String path) {
        if (!"POST".equalsIgnoreCase(method)) {
            throw Http.methodNotAllowed(path + " 只接受 POST");
        }
    }

    private static void requireGet(String method, String path) {
        if (!"GET".equalsIgnoreCase(method)) {
            throw Http.methodNotAllowed(path + " 只接受 GET");
        }
    }

    // ------------------------------------------------------------------

    private void login(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = Json.parseObject(Http.readBody(req, config.maxBodyBytes()));
        String username = asString(body.get("username"));
        String password = asString(body.get("password"));

        AuthService.LoginResult result = auth.login(username, password);
        switch (result.status()) {
            case OK -> {
                SessionStore.Session session = result.session();
                setSessionCookie(req, resp, session);
                long expiresIn = Duration.between(Instant.now(),
                        session.seenAt().plus(config.sessionIdle())).getSeconds();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("token", session.token());
                payload.put("username", session.username());
                payload.put("role", session.role().name());
                payload.put("expiresInSeconds", Math.max(0, expiresIn));
                payload.put("idleTimeoutMinutes", config.sessionIdle().toMinutes());
                payload.put("absoluteTimeoutHours", config.sessionMax().toHours());
                Http.ok(resp, payload);
            }
            case LOCKED -> {
                long retry = Math.max(1, result.retryAfterSeconds());
                resp.setHeader("Retry-After", String.valueOf(retry));
                throw Http.tooManyRequests("登录失败次数过多，账号已临时锁定，请在 "
                        + retry + " 秒后重试。");
            }
            case NO_ACCOUNTS -> throw Http.forbidden("HARKEN_NO_ACCOUNTS",
                    "系统没有配置任何可用账号（且未生成临时密码），人类通道不可登录。");
            default -> throw Http.unauthorized("HARKEN_BAD_CREDENTIALS", "用户名或密码错误。");
        }
    }

    private void setSessionCookie(HttpServletRequest req, HttpServletResponse resp,
                                  SessionStore.Session session) {
        StringBuilder cookie = new StringBuilder();
        cookie.append(Http.SESSION_COOKIE).append('=').append(session.token());
        cookie.append("; Path=").append(config.basePath());
        cookie.append("; Max-Age=").append(config.sessionIdle().toSeconds());
        cookie.append("; HttpOnly");
        // SameSite=Strict：控制台的每一个请求都是同源发起的，没有理由放开跨站携带
        cookie.append("; SameSite=Strict");
        if (req.isSecure()) {
            cookie.append("; Secure");
        }
        resp.addHeader("Set-Cookie", cookie.toString());
    }

    private void logout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String token = Http.sessionToken(req);
        boolean removed = auth.logout(token);
        clearSessionCookie(req, resp);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loggedOut", removed);
        Http.ok(resp, payload);
    }

    private void clearSessionCookie(HttpServletRequest req, HttpServletResponse resp) {
        StringBuilder cookie = new StringBuilder();
        cookie.append(Http.SESSION_COOKIE).append("=; Path=").append(config.basePath());
        cookie.append("; Max-Age=0; HttpOnly; SameSite=Strict");
        if (req.isSecure()) {
            cookie.append("; Secure");
        }
        resp.addHeader("Set-Cookie", cookie.toString());
    }

    private void me(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionStore.Session session = requireSession(req);
        long remaining = session.remainingMillis(Instant.now(),
                config.sessionIdle(), config.sessionMax());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", session.username());
        payload.put("role", session.role().name());
        payload.put("remainingSeconds", remaining / 1000);
        payload.put("canCommit", session.role().canCommit());
        Http.ok(resp, payload);
    }

    private void reload(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SessionStore.Session session = requireSession(req);
        if (!session.role().canReloadCredentials()) {
            throw Http.forbidden("HARKEN_FORBIDDEN_RELOAD",
                    "只有 ADMIN 角色可以重载凭据。当前角色：" + session.role());
        }
        AuthService.ReloadResult result = auth.reload();
        clearSessionCookie(req, resp);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userCount", result.userCount());
        payload.put("droppedSessions", result.droppedSessions());
        payload.put("disabledUsers", result.disabledUsers());
        payload.put("note", "所有会话已失效（包括本次），请重新登录。"
                + "重载只重新读取 env: 引用的环境变量；改 yml 需要重启。");
        Http.ok(resp, payload);
    }

    private SessionStore.Session requireSession(HttpServletRequest req) {
        String token = Http.sessionToken(req);
        SessionStore.Session session = auth.touch(token);
        if (session == null) {
            throw Http.unauthorized("HARKEN_UNAUTHORIZED", "未登录或会话已失效，请重新登录。");
        }
        return session;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
