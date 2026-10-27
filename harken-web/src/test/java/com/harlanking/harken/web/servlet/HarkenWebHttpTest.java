package com.harlanking.harken.web.servlet;

import com.harlanking.harken.core.HarkenOptions;
import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.engine.GroovyScriptEngine;
import com.harlanking.harken.core.impl.DefaultScriptValidator;
import com.harlanking.harken.core.impl.MapBeanResolver;
import com.harlanking.harken.core.impl.NoopAuditStore;
import com.harlanking.harken.core.metadata.ClassIndex;
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
 * HTTP 层的端到端行为：走真实的 servlet、真实的 Groovy 引擎，只把容器换成假对象。
 *
 * <h2>为什么值得写这一层</h2>
 * <p>「AI 严格只读」「READONLY 不能 commit」这类保证，单元测试测不到 ——
 * 它们是由 servlet 的路由、认证、类型构造三者共同成立的。
 * 只测 {@code HarkenService} 全绿，也不能证明 HTTP 上没开一个后门。
 *
 * <p>测试类放在 {@code servlet} 包里，是为了能直接调 {@code service(...)}
 * 这个 protected 方法，不必为了测试把它改成 public。
 */
class HarkenWebHttpTest {

    private HarkenWeb web;

    /** 一个 bean 多到会触发 gzip 的实例，用来验证压缩分支。 */
    private HarkenWeb webLarge;

    /** 带真实类名索引的实例：验证「脚本里的简名」这条路。 */
    private HarkenWeb webIndexed;

    /** 一个永远「有事务」的替身，让写操作能通过 SR-02 检查。 */
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

    /** 演示用的业务 bean。 */
    public static final class OrderService {
        /** @return 模拟订单数 */
        public int count() {
            return 7;
        }

        /** @return 模拟订单号 */
        public String findNo(long id) {
            return "SO-" + id;
        }
    }

    @BeforeEach
    void setUp() {
        HarkenService service = new HarkenService(
                new GroovyScriptEngine(getClass().getClassLoader(), 16),
                new MapBeanResolver(Map.of("orderService", new OrderService())),
                List.of(),
                List.of(new DefaultScriptValidator()),
                List.of(NoopAuditStore.INSTANCE),
                FAKE_TX,
                HarkenOptions.defaults());

        HarkenWebConfig config = HarkenWebConfig.builder()
                .users(List.of(
                        new HarkenWebConfig.UserSpec("admin", PasswordHash.hash("admin-pw", 1_000), "ADMIN"),
                        new HarkenWebConfig.UserSpec("reader", PasswordHash.hash("reader-pw", 1_000), "READONLY")))
                .aiToken("ai-token-123")
                .generatePasswordIfNoUsers(false)
                .maxLoginFailures(5)
                .build();

        web = HarkenWeb.create(service, ClassIndex.empty(), config);

        // 60 个 bean：能力清单会超过 1KB，从而走进 gzip 分支
        java.util.Map<String, Object> many = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 60; i++) {
            many.put("orderService" + i, new OrderService());
        }
        HarkenService serviceLarge = new HarkenService(
                new GroovyScriptEngine(getClass().getClassLoader(), 16),
                new MapBeanResolver(many),
                List.of(), List.of(new DefaultScriptValidator()),
                List.of(NoopAuditStore.INSTANCE), FAKE_TX, HarkenOptions.defaults());
        webLarge = HarkenWeb.create(serviceLarge, ClassIndex.empty(), config);

        webIndexed = HarkenWeb.create(service, ClassIndex.of(List.of(
                "com.harlanking.harken.web.servlet.MetadataJson",
                "com.demo.sample.String")), config);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private FakeHttp.Response call(HarkenServlet servlet, FakeHttp.RequestBuilder builder) throws Exception {
        HttpServletRequest req = builder.build();
        FakeHttp.ResponseHolder holder = FakeHttp.response();
        HttpServletResponse resp = holder.proxy();
        servlet.service(req, resp);
        return holder.snapshot();
    }

    private FakeHttp.Response loginResponse(String username, String password) throws Exception {
        FakeHttp.Response r = call(web.authServlet(), new FakeHttp.RequestBuilder()
                .method("POST")
                .path("/login")
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
        assertEquals(200, r.status(), r.text());
        return r;
    }

    private String login(String username, String password) throws Exception {
        String token = FakeHttp.field(loginResponse(username, password).text(), "token");
        assertNotNull(token);
        return token;
    }

    /** 每个 {@link HarkenWeb} 实例有自己的会话表，跨实例拿 token 必须在该实例上登录。 */
    private String loginOn(HarkenWeb target, String username, String password) throws Exception {
        FakeHttp.Response r = call(target.authServlet(), new FakeHttp.RequestBuilder()
                .method("POST")
                .path("/login")
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
        assertEquals(200, r.status(), r.text());
        String token = FakeHttp.field(r.text(), "token");
        assertNotNull(token);
        return token;
    }

    private FakeHttp.Response execute(String token, String script, Boolean commit) throws Exception {
        StringBuilder body = new StringBuilder("{\"script\":").append(com.harlanking.harken.web.json.Json.write(script));
        if (commit != null) {
            body.append(",\"commit\":").append(commit);
        }
        body.append('}');
        return call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/execute")
                .header(Http.SESSION_HEADER, token)
                .body(body.toString()));
    }

    // ------------------------------------------------------------------
    // 人类通道
    // ------------------------------------------------------------------

    @Test
    @DisplayName("没有会话时 /api/execute 返回 401")
    void executeRequiresSession() throws Exception {
        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/execute").body("{\"script\":\"return 1\"}"));

        assertEquals(401, r.status());
        assertEquals("HARKEN_UNAUTHORIZED", FakeHttp.field(r.text(), "errorType"));
    }

    @Test
    @DisplayName("登录后能执行脚本，返回值出现在 result 里，通道标记为 human")
    void executeAfterLogin() throws Exception {
        FakeHttp.Response loginResp = loginResponse("admin", "admin-pw");
        assertTrue(loginResp.setCookies().stream().anyMatch(c -> c.startsWith(Http.SESSION_COOKIE + "=")),
                "登录应同时下发 Cookie，方便浏览器使用");

        String token = FakeHttp.field(loginResp.text(), "token");
        FakeHttp.Response r = execute(token, "orderService.count()", null);

        assertEquals(200, r.status(), r.text());
        assertEquals("7", FakeHttp.field(r.text(), "result"));
        assertEquals("human", FakeHttp.field(r.text(), "channel"));
        assertTrue(r.setCookies().isEmpty(), "execute 不该重复下发 Cookie");
    }

    @Test
    @DisplayName("READONLY 角色 commit=true 被 403 拒绝，且不会被静默降级")
    void readonlyRoleCannotCommit() throws Exception {
        String token = login("reader", "reader-pw");

        FakeHttp.Response r = execute(token, "return 1", true);

        assertEquals(403, r.status());
        assertEquals("HARKEN_FORBIDDEN_COMMIT", FakeHttp.field(r.text(), "errorType"));
    }

    @Test
    @DisplayName("READONLY 角色 commit=false 可以试跑")
    void readonlyRoleCanDryRun() throws Exception {
        String token = login("reader", "reader-pw");

        FakeHttp.Response r = execute(token, "return 1+1", false);

        assertEquals(200, r.status(), r.text());
        assertEquals("2", FakeHttp.field(r.text(), "result"));
        assertEquals("true", FakeHttp.field(r.text(), "rolledBack"));
    }

    @Test
    @DisplayName("密码错误返回 401，且响应里没有「用户不存在」这种区分")
    void wrongPasswordIs401() throws Exception {
        FakeHttp.Response r = call(web.authServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/login")
                .body("{\"username\":\"admin\",\"password\":\"wrong\"}"));

        assertEquals(401, r.status());
        assertEquals("HARKEN_BAD_CREDENTIALS", FakeHttp.field(r.text(), "errorType"));
    }

    @Test
    @DisplayName("登出后同一个令牌立即失效")
    void logoutInvalidatesToken() throws Exception {
        String token = login("admin", "admin-pw");

        FakeHttp.Response logout = call(web.authServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/logout").header(Http.SESSION_HEADER, token));
        assertEquals(200, logout.status());

        assertEquals(401, execute(token, "return 1", null).status());
    }

    @Test
    @DisplayName("/me 返回角色与剩余时间")
    void meReportsIdentity() throws Exception {
        String token = login("reader", "reader-pw");

        FakeHttp.Response r = call(web.authServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/me").header(Http.SESSION_HEADER, token));

        assertEquals(200, r.status());
        assertEquals("reader", FakeHttp.field(r.text(), "username"));
        assertEquals("READONLY", FakeHttp.field(r.text(), "role"));
        assertEquals("false", FakeHttp.field(r.text(), "canCommit"));
    }

    @Test
    @DisplayName("非 ADMIN 不能重载凭据")
    void reloadRequiresAdmin() throws Exception {
        String token = login("reader", "reader-pw");

        FakeHttp.Response r = call(web.authServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/reload").header(Http.SESSION_HEADER, token));

        assertEquals(403, r.status());
        assertEquals("HARKEN_FORBIDDEN_RELOAD", FakeHttp.field(r.text(), "errorType"));
    }

    @Test
    @DisplayName("ADMIN 重载后自己也被踢下线，并提示需要重新登录")
    void reloadInvalidatesOwnSession() throws Exception {
        String token = login("admin", "admin-pw");

        FakeHttp.Response r = call(web.authServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/reload").header(Http.SESSION_HEADER, token));

        assertEquals(200, r.status(), r.text());
        assertEquals("true", FakeHttp.field(r.text(), "ok"));
        assertEquals(401, execute(token, "return 1", null).status());
    }

    @Test
    @DisplayName("/compile 对语法错误返回 400 且带上行号")
    void compileReportsLineNumber() throws Exception {
        String token = login("admin", "admin-pw");

        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/compile")
                .header(Http.SESSION_HEADER, token)
                .body("{\"script\":\"def x = \\n\\nif (\"}"));

        assertEquals(400, r.status(), r.text());
        assertEquals("COMPILATION", FakeHttp.field(r.text(), "errorType"));
        assertNotNull(FakeHttp.field(r.text(), "line"));
    }

    @Test
    @DisplayName("/compile 通过返回 ok")
    void compileAcceptsValidScript() throws Exception {
        String token = login("admin", "admin-pw");

        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/compile")
                .header(Http.SESSION_HEADER, token)
                .body("{\"script\":\"orderService.count()\"}"));

        assertEquals(200, r.status(), r.text());
        assertEquals("true", FakeHttp.field(r.text(), "ok"));
    }

    @Test
    @DisplayName("/metadata 给出补全索引，?bean= 按需给方法")
    void metadataServesIndexAndMethods() throws Exception {
        String token = login("admin", "admin-pw");

        FakeHttp.Response index = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/metadata").header(Http.SESSION_HEADER, token));

        assertEquals(200, index.status());
        // 索引只给名字，不给方法：几百个 Bean 的方法全量塞进首屏是最初那版的错误
        assertTrue(index.text().contains("\"beanTypes\""), index.text());
        assertTrue(index.text().contains("orderService"), index.text());
        assertFalse(index.text().contains("\"count\""), index.text());

        FakeHttp.Response methods = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/metadata").query("bean=orderService")
                .header(Http.SESSION_HEADER, token));

        assertEquals(200, methods.status());
        assertTrue(methods.text().contains("\"count\""), methods.text());
        assertTrue(methods.text().contains("\"static\":false"), methods.text());
    }

    @Test
    @DisplayName("/metadata?type= 读静态方法")
    void metadataServesStaticMethods() throws Exception {
        String token = login("admin", "admin-pw");

        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/metadata").query("type=java.util.UUID")
                .header(Http.SESSION_HEADER, token));

        assertEquals(200, r.status());
        assertTrue(r.text().contains("randomUUID"), r.text());
        assertTrue(r.text().contains("\"static\":true"), r.text());
    }

    @Test
    @DisplayName("/metadata?inst= 给实例方法，链式补全的第二个点号用")
    void metadataServesInstanceMethods() throws Exception {
        String token = login("admin", "admin-pw");

        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/metadata").query("inst=java.util.List")
                .header(Http.SESSION_HEADER, token));

        assertEquals(200, r.status());
        assertTrue(r.text().contains("forEach"), r.text());
        assertTrue(r.text().contains("\"static\":false"), r.text());
        // 返回类型里的 E 要能被前端换回真实类型，所以类型参数名必须给出来
        assertTrue(r.text().contains("\"typeVars\":[\"E\"]"), r.text());
    }

    @Test
    @DisplayName("/metadata?type= 枚举附带常量名（valueOf 只认它）")
    void metadataListsEnumConstants() throws Exception {
        String token = login("admin", "admin-pw");

        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/metadata").query("type=java.time.DayOfWeek")
                .header(Http.SESSION_HEADER, token));

        assertEquals(200, r.status());
        assertTrue(r.text().contains("\"constants\":[\"MONDAY\""), r.text());
        assertTrue(r.text().contains("valueOf"), r.text());
    }

    @Test
    @DisplayName("/metadata?type= 不带点号：按索引给方法，并被 Groovy 默认导入遮蔽时报出来")
    void metadataResolvesBareTypeName() throws Exception {
        String token = loginOn(webIndexed, "admin", "admin-pw");

        // 没 import 的简名：方法表给的是索引里那个类，前端据此补 import
        FakeHttp.Response indexed = call(webIndexed.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/metadata").query("type=MetadataJson")
                .header(Http.SESSION_HEADER, token));
        assertEquals(200, indexed.status());
        assertEquals("com.harlanking.harken.web.servlet.MetadataJson",
                FakeHttp.field(indexed.text(), "owner"));
        assertTrue(indexed.text().contains("\"view\""), indexed.text());
        assertFalse(indexed.text().contains("shadowedBy"), indexed.text());

        // 同名类撞上默认导入：必须如实说「这个名字现在绑的是 java.lang.String」
        FakeHttp.Response shadowed = call(webIndexed.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/metadata").query("type=String")
                .header(Http.SESSION_HEADER, token));
        assertEquals(200, shadowed.status());
        assertEquals("com.demo.sample.String", FakeHttp.field(shadowed.text(), "owner"));
        assertEquals("java.lang.String", FakeHttp.field(shadowed.text(), "shadowedBy"));
    }

    @Test
    @DisplayName("/health 反映事务与 AI 开关状态")
    void healthReportsState() throws Exception {
        String token = login("admin", "admin-pw");

        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/health").header(Http.SESSION_HEADER, token));

        assertEquals(200, r.status());
        assertEquals("true", FakeHttp.field(r.text(), "ok"));
        // 事务信息嵌在 transaction 对象里：supported 与实际描述的来源一致
        assertTrue(r.text().contains("\"supported\":true"), r.text());
        assertTrue(r.text().contains("fake-tx"), r.text());
        assertTrue(r.text().contains("\"ai\":{\"enabled\":true}"), r.text());
    }

    @Test
    @DisplayName("请求体不是合法 JSON 时返回 400 而不是 500")
    void malformedJsonIs400() throws Exception {
        String token = login("admin", "admin-pw");

        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/execute")
                .header(Http.SESSION_HEADER, token)
                .body("{not json"));

        assertEquals(400, r.status());
        assertEquals("HARKEN_BAD_REQUEST", FakeHttp.field(r.text(), "errorType"));
    }

    @Test
    @DisplayName("未知路径返回 404")
    void unknownPathIs404() throws Exception {
        String token = login("admin", "admin-pw");

        FakeHttp.Response r = call(web.humanServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/nope").header(Http.SESSION_HEADER, token));

        assertEquals(404, r.status());
    }

    // ------------------------------------------------------------------
    // AI 通道
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AI 通道没有 Bearer 令牌时返回 401")
    void aiRequiresToken() throws Exception {
        FakeHttp.Response r = call(web.aiServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/query").body("{\"script\":\"return 1\"}"));

        assertEquals(401, r.status());
        assertEquals("HARKEN_AI_UNAUTHORIZED", FakeHttp.field(r.text(), "errorType"));
    }

    @Test
    @DisplayName("AI 通道令牌错误时返回 401")
    void aiRejectsWrongToken() throws Exception {
        FakeHttp.Response r = call(web.aiServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/query")
                .header("Authorization", "Bearer wrong")
                .body("{\"script\":\"return 1\"}"));

        assertEquals(401, r.status());
    }

    @Test
    @DisplayName("AI 通道明确拒绝 commit 参数（400），而不是静默忽略")
    void aiRejectsCommitField() throws Exception {
        FakeHttp.Response r = call(web.aiServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/query")
                .header("Authorization", "Bearer ai-token-123")
                .body("{\"script\":\"return 1\",\"commit\":true}"));

        assertEquals(400, r.status());
        assertTrue(r.text().contains("commit"), r.text());
    }

    @Test
    @DisplayName("AI 通道执行成功，channel=ai，operator 记为 ai，且事务回滚")
    void aiExecutesReadOnly() throws Exception {
        FakeHttp.Response r = call(web.aiServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/query")
                .header("Authorization", "Bearer ai-token-123")
                .body("{\"script\":\"orderService.count()\"}"));

        assertEquals(200, r.status(), r.text());
        assertEquals("7", FakeHttp.field(r.text(), "result"));
        assertEquals("ai", FakeHttp.field(r.text(), "channel"));
        assertEquals("true", FakeHttp.field(r.text(), "rolledBack"));
    }

    @Test
    @DisplayName("AI 通道也走同一个 IP 白名单（由 filter 统一负责）")
    void aiBlockedByIpFilter() throws Exception {
        FakeHttp.RequestBuilder builder = new FakeHttp.RequestBuilder()
                .method("POST").path("/query")
                .header("Authorization", "Bearer ai-token-123")
                .body("{\"script\":\"return 1\"}")
                .remoteAddr("8.8.8.8");

        FakeHttp.ResponseHolder holder = FakeHttp.response();
        boolean[] chainCalled = {false};
        web.ipFilter().doFilter(builder.build(), holder.proxy(),
                (req, resp) -> chainCalled[0] = true);

        assertFalse(chainCalled[0], "不在白名单时不能放行到 servlet");
        assertEquals(403, holder.snapshot().status());
        assertEquals("HARKEN_IP_FORBIDDEN", FakeHttp.field(holder.snapshot().text(), "errorType"));
    }

    @Test
    @DisplayName("回环地址能穿过 IP 过滤器")
    void loopbackPassesIpFilter() throws Exception {
        FakeHttp.RequestBuilder builder = new FakeHttp.RequestBuilder().remoteAddr("127.0.0.1");
        FakeHttp.ResponseHolder holder = FakeHttp.response();
        boolean[] chainCalled = {false};

        web.ipFilter().doFilter(builder.build(), holder.proxy(),
                (req, resp) -> chainCalled[0] = true);

        assertTrue(chainCalled[0]);
    }

    @Test
    @DisplayName("capabilities 超过 1KB 且客户端接受 gzip 时压缩，解压后仍是合法 JSON")
    void capabilitiesSupportsGzip() throws Exception {
        FakeHttp.Response r = call(webLarge.aiServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/capabilities")
                .header("Authorization", "Bearer ai-token-123")
                .header("Accept-Encoding", "gzip"));

        assertEquals(200, r.status());
        assertEquals("gzip", r.header("Content-Encoding"));

        byte[] raw = r.bytes();
        String text;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals("true", FakeHttp.field(text, "ok"));
        assertTrue(text.contains("orderService"), text);
        assertTrue(text.contains("\"readOnly\":true"), text);
        assertTrue(text.length() > 1024, "样本要足够大，否则测不到压缩分支");
    }

    @Test
    @DisplayName("响应很小时不压缩（gzip 反而更大）")
    void smallPayloadNotCompressed() throws Exception {
        FakeHttp.Response r = call(web.aiServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/capabilities")
                .header("Authorization", "Bearer ai-token-123")
                .header("Accept-Encoding", "gzip"));

        assertEquals(200, r.status());
        assertNull(r.header("Content-Encoding"));
    }

    @Test
    @DisplayName("capabilities 不带 Accept-Encoding 时返回明文 JSON")
    void capabilitiesWithoutGzip() throws Exception {
        FakeHttp.Response r = call(web.aiServlet(), new FakeHttp.RequestBuilder()
                .method("GET").path("/capabilities")
                .header("Authorization", "Bearer ai-token-123"));

        assertEquals(200, r.status());
        assertNull(r.header("Content-Encoding"));
        assertTrue(r.text().contains("orderService"));
    }

    @Test
    @DisplayName("AI 通道不存在人类端点（/execute 在 AI servlet 上是 404）")
    void aiHasNoExecuteEndpoint() throws Exception {
        FakeHttp.Response r = call(web.aiServlet(), new FakeHttp.RequestBuilder()
                .method("POST").path("/execute")
                .header("Authorization", "Bearer ai-token-123")
                .body("{\"script\":\"return 1\"}"));

        assertEquals(404, r.status());
    }
}
