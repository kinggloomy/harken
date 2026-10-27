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
import com.harlanking.harken.web.auth.RequestSigner;
import com.harlanking.harken.web.testing.FakeHttp;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 通道签名门（SR-04）的端到端测试：从 HTTP 头一直走到 servlet 的拒绝/放行。
 *
 * <p>为什么单独一个测试类而不是塞进 {@code HarkenWebHttpTest}：那个类的配置里没有签名密钥，
 * 混进来就得为每个用例准备两套实例。这里只测「签名这一层」，配置更聚焦。
 */
class AiSignatureHttpTest {

    private static final String TOKEN = "ai-token-123";
    private static final String SECRET = "shared-signing-secret";

    /**
     * 待签串里的 PATH 取 {@code requestURI}。
     *
     * <p>注意：{@code FakeHttp#getRequestURI()} 返回 {@code "/harken" + pathInfo}，
     * 而 pathInfo 就是测试里 {@code .path("/query")} 传进去的值（fake 不知道 servlet
     * 挂在 {@code /harken/ai/*} 下）。真实部署时 URI 是 {@code /harken/ai/query}，
     * 但服务端与调用方用的是<b>同一个</b> requestURI，所以这里按 fake 的真实值签，语义等价。
     */
    private static final String URI_QUERY = "/harken/query";
    private static final String URI_CAPABILITIES = "/harken/capabilities";

    private static final TransactionBridge FAKE_TX = new TransactionBridge() {
        @Override
        public <T> T inTransaction(Mode mode, java.util.function.Supplier<T> body) {
            return body.get();
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

    @BeforeEach
    void setUp() {
        HarkenService service = new HarkenService(
                new GroovyScriptEngine(getClass().getClassLoader(), 16),
                new MapBeanResolver(Map.of()),
                List.of(), List.of(new DefaultScriptValidator()),
                List.of(NoopAuditStore.INSTANCE), FAKE_TX, HarkenOptions.defaults());

        HarkenWebConfig config = HarkenWebConfig.builder()
                .users(List.of(new HarkenWebConfig.UserSpec(
                        "admin", PasswordHash.hash("admin-pw", 1_000), "ADMIN")))
                .aiToken(TOKEN)
                .aiSigningSecret(SECRET)
                .aiSignatureWindowSeconds(60)
                .generatePasswordIfNoUsers(false)
                .build();

        web = HarkenWeb.create(service, ClassIndex.empty(), config);
    }

    private FakeHttp.Response call(FakeHttp.RequestBuilder builder) throws Exception {
        HttpServletRequest req = builder.build();
        FakeHttp.ResponseHolder holder = FakeHttp.response();
        web.aiServlet().service(req, holder.proxy());
        return holder.snapshot();
    }

    private static String nowTs() {
        return String.valueOf(System.currentTimeMillis() / 1000L);
    }

    private static String body(String script) {
        return "{\"script\":\"" + script + "\"}";
    }

    private static String signQuery(String ts, String nonce, String bodyText) {
        return RequestSigner.sign(SECRET, "POST", URI_QUERY, ts, nonce,
                bodyText.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("配了密钥但没带签名头 → 401 HARKEN_SIGNATURE_REQUIRED")
    void missingSignatureHeaders() throws Exception {
        FakeHttp.Response r = call(new FakeHttp.RequestBuilder()
                .method("POST").path("/query")
                .header("Authorization", "Bearer " + TOKEN)
                .body(body("return 1")));

        assertEquals(401, r.status(), r.text());
        assertEquals("HARKEN_SIGNATURE_REQUIRED", FakeHttp.field(r.text(), "errorType"));
    }

    @Test
    @DisplayName("签名正确 → 200")
    void validSignaturePasses() throws Exception {
        String ts = nowTs();
        String nonce = "nonce-aaaa1111";
        String b = body("return 1");

        FakeHttp.Response r = call(new FakeHttp.RequestBuilder()
                .method("POST").path("/query")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Harken-Timestamp", ts)
                .header("X-Harken-Nonce", nonce)
                .header("X-Harken-Signature", signQuery(ts, nonce, b))
                .body(b));

        assertEquals(200, r.status(), r.text());
        assertEquals("1", FakeHttp.field(r.text(), "result"));
    }

    @Test
    @DisplayName("同一个请求重发第二次 → 401 HARKEN_SIGNATURE_REPLAY（核心验收）")
    void replayIsRejected() throws Exception {
        String ts = nowTs();
        String nonce = "nonce-bbbb2222";
        String b = body("return 1");
        String sig = signQuery(ts, nonce, b);

        FakeHttp.Response first = call(new FakeHttp.RequestBuilder()
                .method("POST").path("/query")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Harken-Timestamp", ts)
                .header("X-Harken-Nonce", nonce)
                .header("X-Harken-Signature", sig)
                .body(b));
        assertEquals(200, first.status(), first.text());

        FakeHttp.Response second = call(new FakeHttp.RequestBuilder()
                .method("POST").path("/query")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Harken-Timestamp", ts)
                .header("X-Harken-Nonce", nonce)
                .header("X-Harken-Signature", sig)
                .body(b));
        assertEquals(401, second.status(), second.text());
        assertEquals("HARKEN_SIGNATURE_REPLAY", FakeHttp.field(second.text(), "errorType"));
    }

    @Test
    @DisplayName("body 被改（签名不变）→ 401 HARKEN_SIGNATURE_INVALID（防篡改）")
    void tamperedBodyRejected() throws Exception {
        String ts = nowTs();
        String nonce = "nonce-cccc3333";
        String original = body("return 1");
        String sig = signQuery(ts, nonce, original);

        FakeHttp.Response r = call(new FakeHttp.RequestBuilder()
                .method("POST").path("/query")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Harken-Timestamp", ts)
                .header("X-Harken-Nonce", nonce)
                .header("X-Harken-Signature", sig)
                .body(body("return 2")));

        assertEquals(401, r.status(), r.text());
        assertEquals("HARKEN_SIGNATURE_INVALID", FakeHttp.field(r.text(), "errorType"));
    }

    @Test
    @DisplayName("时间戳超出窗口 → 401 HARKEN_SIGNATURE_STALE")
    void staleTimestampRejected() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000L - 3600L);
        String nonce = "nonce-dddd4444";
        String b = body("return 1");

        FakeHttp.Response r = call(new FakeHttp.RequestBuilder()
                .method("POST").path("/query")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Harken-Timestamp", ts)
                .header("X-Harken-Nonce", nonce)
                .header("X-Harken-Signature", signQuery(ts, nonce, b))
                .body(b));

        assertEquals(401, r.status(), r.text());
        assertEquals("HARKEN_SIGNATURE_STALE", FakeHttp.field(r.text(), "errorType"));
        assertTrue(r.text().contains("窗口"), r.text());
    }

    @Test
    @DisplayName("Token 错误时先拒 Token（签名对不对都轮不到）")
    void tokenIsCheckedFirst() throws Exception {
        String ts = nowTs();
        String nonce = "nonce-eeee5555";
        String b = body("return 1");

        FakeHttp.Response r = call(new FakeHttp.RequestBuilder()
                .method("POST").path("/query")
                .header("Authorization", "Bearer wrong-token")
                .header("X-Harken-Timestamp", ts)
                .header("X-Harken-Nonce", nonce)
                .header("X-Harken-Signature", signQuery(ts, nonce, b))
                .body(b));

        assertEquals(401, r.status(), r.text());
        assertEquals("HARKEN_AI_UNAUTHORIZED", FakeHttp.field(r.text(), "errorType"));
    }

    @Test
    @DisplayName("GET /capabilities 同样要签名（空 body 参与摘要）")
    void capabilitiesAlsoSigned() throws Exception {
        String ts = nowTs();
        String nonce = "nonce-ffff6666";
        String sig = RequestSigner.sign(SECRET, "GET", URI_CAPABILITIES, ts, nonce, new byte[0]);

        FakeHttp.Response unsigned = call(new FakeHttp.RequestBuilder()
                .method("GET").path("/capabilities")
                .header("Authorization", "Bearer " + TOKEN));
        assertEquals(401, unsigned.status(), unsigned.text());

        FakeHttp.Response signed = call(new FakeHttp.RequestBuilder()
                .method("GET").path("/capabilities")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Harken-Timestamp", ts)
                .header("X-Harken-Nonce", nonce)
                .header("X-Harken-Signature", sig));
        assertEquals(200, signed.status(), signed.text());
    }
}
