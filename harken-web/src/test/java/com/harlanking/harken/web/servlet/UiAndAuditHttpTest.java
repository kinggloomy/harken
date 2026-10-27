package com.harlanking.harken.web.servlet;

import com.harlanking.harken.core.HarkenOptions;
import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.engine.GroovyScriptEngine;
import com.harlanking.harken.core.impl.DefaultScriptValidator;
import com.harlanking.harken.core.impl.MapBeanResolver;
import com.harlanking.harken.core.impl.MemoryAuditStore;
import com.harlanking.harken.core.spi.TransactionBridge;
import com.harlanking.harken.web.HarkenWeb;
import com.harlanking.harken.web.HarkenWebConfig;
import com.harlanking.harken.web.auth.PasswordHash;
import com.harlanking.harken.web.testing.FakeHttp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台页面、审计反查、跨站防护三块的 HTTP 行为。
 *
 * <h2>为什么这几条一定要有测试</h2>
 * <ul>
 *   <li><b>资源服务的路径穿越</b>：{@code /harken/assets/../../etc/passwd} 这类请求
 *       在没有测试时不会有人去试，而它一旦成立就是任意文件读取。</li>
 *   <li><b>ETag / 304</b>：写错了不会报错，只会让每次刷新都重传 400KB —— 没人会注意到，
 *       直到有人在 SSH 隧道后面用控制台。</li>
 *   <li><b>Origin 校验</b>：这是「能写库的端点」的第二道防线，必须证明它真的会拒。</li>
 *   <li><b>{@code /api/audits}</b>：控制台「最近执行」面板的唯一数据源。</li>
 * </ul>
 */
class UiAndAuditHttpTest {

    /** 有事务的替身，让写操作能过 SR-02。 */
    private static final TransactionBridge FAKE_TX = new TransactionBridge() {
        @Override
        public <T> T inTransaction(Mode mode, Supplier<T> work) {
            return work.get();
        }

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public String description() {
            return "fake-tx";
        }
    };

    private HarkenWeb web;
    private MemoryAuditStore memory;

    /** 演示 bean。 */
    public static final class OrderService {
        /** @return 固定 7，便于断言 */
        public int count() {
            return 7;
        }
    }

    @BeforeEach
    void setUp() {
        memory = new MemoryAuditStore(50);
        Map<String, Object> beans = Map.of("orderService", new OrderService());
        HarkenService service = new HarkenService(
                new GroovyScriptEngine(getClass().getClassLoader(), 16),
                new MapBeanResolver(beans),
                List.of(),
                List.of(new DefaultScriptValidator()),
                List.of(memory),
                FAKE_TX,
                HarkenOptions.defaults());

        HarkenWebConfig config = HarkenWebConfig.builder()
                .users(List.of(new HarkenWebConfig.UserSpec(
                        "admin", PasswordHash.hash("admin-pw", 1_000), "ADMIN")))
                .generatePasswordIfNoUsers(false)
                .environment("staging")
                .build();
        web = HarkenWeb.create(service, com.harlanking.harken.core.metadata.ClassIndex.empty(), config);
    }

    private FakeHttp.Response call(HarkenServlet servlet, FakeHttp.RequestBuilder builder)
            throws Exception {
        HttpServletRequest req = builder.build();
        FakeHttp.ResponseHolder holder = FakeHttp.response();
        HttpServletResponse resp = holder.proxy();
        servlet.service(req, resp);
        return holder.snapshot();
    }

    private String login() throws Exception {
        FakeHttp.Response r = call(web.authServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/login")
                .body("{\"username\":\"admin\",\"password\":\"admin-pw\"}"));
        assertEquals(200, r.status(), r.text());
        String token = FakeHttp.field(r.text(), "token");
        assertNotNull(token);
        return token;
    }

    // ------------------------------------------------------------------
    // 页面与资源
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET / 返回登录页，并带严格 CSP")
    void servesIndexWithStrictCsp() throws Exception {
        FakeHttp.Response r = call(web.uiServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/"));

        assertEquals(200, r.status());
        assertTrue(r.contentType().startsWith("text/html"), r.contentType());
        assertTrue(r.text().contains("Harken"), "页面里至少要有产品名");
        assertTrue(r.text().contains("assets/app.js"), "登录页要引用同源的 app.js");

        String csp = r.header("Content-Security-Policy");
        assertNotNull(csp, "控制台能提交事务，必须有 CSP");
        assertTrue(csp.contains("default-src 'none'"), csp);
        assertFalse(csp.contains("unsafe-inline"), "CSP 不该放开内联脚本：" + csp);
        assertFalse(csp.contains("unsafe-eval"), "CSP 不该放开 eval：" + csp);
        assertEquals("DENY", r.header("X-Frame-Options"), "控制台不许被 iframe 套住");
        assertEquals("no-store", r.header("Cache-Control"), "index 不能缓存，否则升级后仍是旧页面");
    }

    @Test
    @DisplayName("资源按后缀给出正确 MIME，并带 ETag")
    void servesAssetsWithContentTypeAndEtag() throws Exception {
        FakeHttp.Response js = call(web.uiServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/assets/app.js"));
        assertEquals(200, js.status());
        assertTrue(js.contentType().startsWith("text/javascript"), js.contentType());
        assertNotNull(js.header("ETag"));
        assertEquals("no-cache", js.header("Cache-Control"), "资源要重新验证，不能盲缓存");

        FakeHttp.Response css = call(web.uiServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/assets/app.css"));
        assertEquals(200, css.status());
        assertTrue(css.contentType().startsWith("text/css"), css.contentType());
    }

    @Test
    @DisplayName("接受 gzip 时压缩大资源，解压后与原文一致")
    void gzipsLargeAssets() throws Exception {
        FakeHttp.Response plain = call(web.uiServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/assets/vendor/codemirror.js"));
        assertEquals(200, plain.status());

        FakeHttp.Response zipped = call(web.uiServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/assets/vendor/codemirror.js")
                .header("Accept-Encoding", "gzip"));

        assertEquals("gzip", zipped.header("Content-Encoding"));
        assertEquals("Accept-Encoding", zipped.header("Vary"),
                "同一个 URL 有两种表示，必须告诉缓存按编码区分");
        assertTrue(zipped.bytes().length < plain.bytes().length / 2,
                "CodeMirror 至少能压掉一半，实际 " + zipped.bytes().length + "/" + plain.bytes().length);

        String restored;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(zipped.bytes()))) {
            restored = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(plain.text(), restored);
    }

    @Test
    @DisplayName("带 If-None-Match 时返回 304 且没有响应体")
    void revalidatesWithEtag() throws Exception {
        FakeHttp.Response first = call(web.uiServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/assets/app.css"));
        String etag = first.header("ETag");
        assertNotNull(etag);

        FakeHttp.Response again = call(web.uiServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/assets/app.css").header("If-None-Match", etag));

        assertEquals(304, again.status());
        assertEquals(0, again.bytes().length, "304 不该带响应体");
    }

    @Test
    @DisplayName("HEAD 只回头不回体")
    void headHasNoBody() throws Exception {
        FakeHttp.Response r = call(web.uiServlet(), new FakeHttp.RequestBuilder()
                .method("HEAD").path("/assets/app.css"));
        assertEquals(200, r.status());
        assertEquals(0, r.bytes().length);
        assertNotNull(r.header("ETag"));
    }

    @Test
    @DisplayName("路径穿越一律 404，找不到的资源也是 404")
    void rejectsTraversalAndMissing() throws Exception {
        String[] attacks = {
                "/assets/../../../../etc/passwd",
                "/assets/..%2f..%2fetc/passwd",
                "/assets/vendor/../../../secret",
                "/assets//etc/passwd",
                "/etc/passwd"
        };
        for (String attack : attacks) {
            FakeHttp.Response r = call(web.uiServlet(), new FakeHttp.RequestBuilder()
                    .method("GET").path(attack));
            assertEquals(404, r.status(), "应当拒绝：" + attack);
            assertFalse(r.text().contains("root:"), "绝不能把文件内容吐出来：" + attack);
        }

        assertEquals(404, call(web.uiServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/assets/nope.js")).status());
    }

    @Test
    @DisplayName("资源名白名单：只认字母数字和 ._-/，且禁用 ..")
    void safeNameRules() {
        assertTrue(UiServlet.isSafeName("app.js"));
        assertTrue(UiServlet.isSafeName("vendor/codemirror.js"));
        assertTrue(UiServlet.isSafeName("LICENSE-CodeMirror.txt"));

        assertFalse(UiServlet.isSafeName(""));
        assertFalse(UiServlet.isSafeName("../app.js"));
        assertFalse(UiServlet.isSafeName("a/../../b"));
        assertFalse(UiServlet.isSafeName("/etc/passwd"));
        assertFalse(UiServlet.isSafeName("a b.js"));
        assertFalse(UiServlet.isSafeName("a\\b.js"));
        assertFalse(UiServlet.isSafeName("a%2e%2e/b"));
        assertFalse(UiServlet.isSafeName("a\u0000.js"));
    }

    @Test
    @DisplayName("页面只接受 GET/HEAD")
    void pageAcceptsGetOnly() throws Exception {
        FakeHttp.Response r = call(web.uiServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/"));
        assertEquals(405, r.status());
    }

    // ------------------------------------------------------------------
    // /api/audits
    // ------------------------------------------------------------------

    @Test
    @DisplayName("/api/audits 需要会话，返回最近记录且不含脚本全文")
    void auditsRequiresSessionAndReturnsPreview() throws Exception {
        assertEquals(401, call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/audits")).status());

        String token = login();
        call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/execute").header(Http.SESSION_HEADER, token)
                .body("{\"script\":\"// 改一下订单\\nreturn orderService.count()\",\"commit\":false}"));

        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/audits").query("limit=5")
                .header(Http.SESSION_HEADER, token));

        assertEquals(200, r.status(), r.text());
        assertEquals("true", FakeHttp.field(r.text(), "queryable"));
        assertTrue(r.text().contains("\"records\""), r.text());
        // 首行非空的注释就是预览
        assertTrue(r.text().contains("// 改一下订单"), r.text());
        // 全文里第二行不该出现 —— 列表只给首行，整段脚本留给 localStorage 与审计表
        assertFalse(r.text().contains("return orderService.count()"), r.text());
    }

    @Test
    @DisplayName("/api/audits 的 limit 非法时 400，超大时被压到硬顶")
    void auditsValidatesLimit() throws Exception {
        String token = login();

        assertEquals(400, call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/audits").query("limit=abc")
                .header(Http.SESSION_HEADER, token)).status());

        FakeHttp.Response huge = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/audits").query("limit=100000")
                .header(Http.SESSION_HEADER, token));
        assertEquals(200, huge.status(), "过大的 limit 该被压到硬顶，而不是报错");
        assertEquals(String.valueOf(HumanApiServlet.MAX_AUDIT_LIMIT),
                FakeHttp.field(huge.text(), "limit"));
    }

    // ------------------------------------------------------------------
    // /api/health
    // ------------------------------------------------------------------

    @Test
    @DisplayName("/api/health 带出环境、守卫描述、可提交标记 —— 状态条的全部数据")
    void healthCarriesStatusBarFacts() throws Exception {
        String token = login();
        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/health").header(Http.SESSION_HEADER, token));

        assertEquals(200, r.status(), r.text());
        assertEquals("staging", FakeHttp.field(r.text(), "environment"));
        assertEquals("true", FakeHttp.field(r.text(), "canCommit"));
        assertTrue(r.text().contains("\"queryable\":true"), "审计可反查要告诉前端：" + r.text());
        assertTrue(r.text().contains("none"), "没装守卫时要明说 none，不能留空让前端猜");
    }

    // ------------------------------------------------------------------
    // 跨站防护
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Origin 与 Host 不一致时，execute 直接 403")
    void crossOriginExecuteIsRejected() throws Exception {
        String token = login();

        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/execute")
                .header(Http.SESSION_HEADER, token)
                .header("Host", "127.0.0.1:8080")
                .header("Origin", "https://evil.example")
                .body("{\"script\":\"return 1\"}"));

        assertEquals(403, r.status(), r.text());
        assertEquals("HARKEN_CROSS_ORIGIN", FakeHttp.field(r.text(), "errorType"));
    }

    @Test
    @DisplayName("Origin 与 Host 一致时放行；没有 Origin 也放行（curl 不带）")
    void sameOriginAndNoOriginPass() throws Exception {
        String token = login();

        FakeHttp.Response same = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/execute")
                .header(Http.SESSION_HEADER, token)
                .header("Host", "admin.internal:8080")
                .header("Origin", "https://admin.internal:8080")
                .body("{\"script\":\"return 1\"}"));
        assertEquals(200, same.status(), same.text());

        FakeHttp.Response noOrigin = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/execute")
                .header(Http.SESSION_HEADER, token)
                .body("{\"script\":\"return 2\"}"));
        assertEquals(200, noOrigin.status(), noOrigin.text());
        assertEquals("2", FakeHttp.field(noOrigin.text(), "result"));
    }

    @Test
    @DisplayName("Origin 为 null（沙箱 iframe）也算跨站")
    void nullOriginIsCrossSite() throws Exception {
        String token = login();
        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/execute")
                .header(Http.SESSION_HEADER, token)
                .header("Host", "127.0.0.1:8080")
                .header("Origin", "null")
                .body("{\"script\":\"return 1\"}"));

        assertEquals(403, r.status());
        assertEquals("HARKEN_CROSS_ORIGIN", FakeHttp.field(r.text(), "errorType"));
    }

    @Test
    @DisplayName("执行结果里带 traceId 与影响行数 —— 「能追溯」要立刻兑现")
    void executeResultCarriesTraceAndRows() throws Exception {
        String token = login();
        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/execute").header(Http.SESSION_HEADER, token)
                .body("{\"script\":\"return orderService.count()\"}"));

        assertEquals(200, r.status(), r.text());
        String traceId = FakeHttp.field(r.text(), "traceId");
        assertNotNull(traceId, "结果里必须有 traceId，否则找日志还得先查库");
        assertEquals(16, traceId.length(), "traceId 是 16 位十六进制");
        // 没装守卫时是 -1（未测量），不是 0
        assertEquals("-1", FakeHttp.field(r.text(), "affectedRows"));
        assertNull(FakeHttp.field(r.text(), "errorType"));
    }
}
