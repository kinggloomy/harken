package com.harlanking.harken.web.testing;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用动态代理伪造 Servlet 请求/响应，避免为一个假对象引一整个测试框架。
 *
 * <h2>为什么用 {@link Proxy} 而不是实现接口</h2>
 * <p>{@code HttpServletRequest} 有六十来个方法，手写实现要写六十行无意义的返回，
 * 而且 Servlet 每次升级加方法就要补。动态代理只处理「我们真的会调到的那几个」，
 * 其余一律返回类型默认值 —— 测试代码只出现在有意义的地方。
 *
 * <p>代价是调用到未实现的方法时不会编译报错。所以本类只用于 Harken 自己的 servlet，
 * 它们用到的请求方法集合是封闭且已知的。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class FakeHttp {

    private FakeHttp() {
    }

    /**
     * 一次响应的快照。
     */
    public static final class Response {

        private int status = 200;
        private String contentType;
        private String characterEncoding;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final List<String> setCookies = new ArrayList<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private boolean committed;

        /**
         * @return HTTP 状态码
         */
        public int status() {
            return status;
        }

        /**
         * @return 响应体文本（UTF-8）
         */
        public String text() {
            return body.toString(StandardCharsets.UTF_8);
        }

        /**
         * @return 响应体字节
         */
        public byte[] bytes() {
            return body.toByteArray();
        }

        /**
         * @param name 头名，大小写不敏感
         * @return 头值，没有则 {@code null}
         */
        public String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }

        /**
         * @return 所有 Set-Cookie 头
         */
        public List<String> setCookies() {
            return List.copyOf(setCookies);
        }

        /**
         * @return Content-Type
         */
        public String contentType() {
            return contentType;
        }
    }

    /**
     * 请求构造器。
     */
    public static final class RequestBuilder {

        private String method = "POST";
        private String pathInfo = "";
        private String remoteAddr = "127.0.0.1";
        private String queryString;
        private boolean secure;
        private byte[] body = new byte[0];
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final List<Cookie> cookies = new ArrayList<>();

        /**
         * 构造器。
         */
        public RequestBuilder() {
        }

        /** @param v 方法 @return 自身 */
        public RequestBuilder method(String v) {
            this.method = v;
            return this;
        }

        /** @param v pathInfo @return 自身 */
        public RequestBuilder path(String v) {
            this.pathInfo = v;
            return this;
        }

        /** @param v 来源地址 @return 自身 */
        public RequestBuilder remoteAddr(String v) {
            this.remoteAddr = v;
            return this;
        }

        /** @param v 查询串 @return 自身 */
        public RequestBuilder query(String v) {
            this.queryString = v;
            return this;
        }

        /** @param v 是否 HTTPS @return 自身 */
        public RequestBuilder secure(boolean v) {
            this.secure = v;
            return this;
        }

        /** @param v 请求体文本（UTF-8） @return 自身 */
        public RequestBuilder body(String v) {
            this.body = v == null ? new byte[0] : v.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        /** @param name 头名 @param value 头值 @return 自身 */
        public RequestBuilder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        /** @param name Cookie 名 @param value Cookie 值 @return 自身 */
        public RequestBuilder cookie(String name, String value) {
            this.cookies.add(new Cookie(name, value));
            return this;
        }

        /**
         * @return 可传给 servlet 的请求
         */
        public HttpServletRequest build() {
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getMethod":
                        return this.method;
                    case "getPathInfo":
                        return this.pathInfo;
                    case "getRemoteAddr":
                        return this.remoteAddr;
                    case "getQueryString":
                        return this.queryString;
                    case "isSecure":
                        return this.secure;
                    case "getRequestURI":
                        return "/harken" + (this.pathInfo == null ? "" : this.pathInfo);
                    case "getHeader":
                        return this.headers.get((String) args[0]);
                    case "getCookies":
                        return this.cookies.isEmpty() ? null : this.cookies.toArray(new Cookie[0]);
                    case "getContentLength":
                        return this.body.length;
                    case "getContentLengthLong":
                        return (long) this.body.length;
                    case "getInputStream":
                        return new FakeInputStream(this.body);
                    case "getParameter":
                        return queryParam(this.queryString, (String) args[0]);
                    case "getCharacterEncoding":
                        return "UTF-8";
                    case "getContentType":
                        return "application/json";
                    case "toString":
                        return "FakeRequest[" + this.method + " " + this.pathInfo + "]";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method);
                }
            };
            return (HttpServletRequest) Proxy.newProxyInstance(
                    FakeHttp.class.getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class}, handler);
        }
    }

    /**
     * 建一个响应对象。
     *
     * @return 响应代理与快照的组合
     */
    public static ResponseHolder response() {
        return new ResponseHolder();
    }

    /**
     * 响应代理 + 快照。
     */
    public static final class ResponseHolder {

        private final Response snapshot = new Response();
        private final HttpServletResponse proxy;

        private ResponseHolder() {
            InvocationHandler handler = (p, method, args) -> {
                switch (method.getName()) {
                    case "setStatus":
                        snapshot.status = (Integer) args[0];
                        return null;
                    case "getStatus":
                        return snapshot.status;
                    case "setContentType":
                        snapshot.contentType = (String) args[0];
                        return null;
                    case "getContentType":
                        return snapshot.contentType;
                    case "setCharacterEncoding":
                        snapshot.characterEncoding = (String) args[0];
                        return null;
                    case "getCharacterEncoding":
                        return snapshot.characterEncoding;
                    case "setHeader":
                        snapshot.headers.put((String) args[0], (String) args[1]);
                        return null;
                    case "addHeader":
                        String name = (String) args[0];
                        if ("Set-Cookie".equalsIgnoreCase(name)) {
                            snapshot.setCookies.add((String) args[1]);
                        } else {
                            snapshot.headers.put(name, (String) args[1]);
                        }
                        return null;
                    case "getHeader":
                        return snapshot.header((String) args[0]);
                    case "setContentLength":
                        snapshot.headers.put("Content-Length", String.valueOf(args[0]));
                        return null;
                    case "setContentLengthLong":
                        snapshot.headers.put("Content-Length", String.valueOf(args[0]));
                        return null;
                    case "getOutputStream":
                        return new FakeOutputStream(snapshot.body);
                    case "getWriter":
                        return new java.io.PrintWriter(new java.io.OutputStreamWriter(
                                snapshot.body, StandardCharsets.UTF_8), true);
                    case "isCommitted":
                        return snapshot.committed;
                    case "flushBuffer":
                        snapshot.committed = true;
                        return null;
                    case "reset":
                        snapshot.body.reset();
                        return null;
                    case "toString":
                        return "FakeResponse[" + snapshot.status + "]";
                    case "hashCode":
                        return System.identityHashCode(p);
                    case "equals":
                        return p == args[0];
                    default:
                        return defaultValue(method);
                }
            };
            this.proxy = (HttpServletResponse) Proxy.newProxyInstance(
                    FakeHttp.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class}, handler);
        }

        /**
         * @return 响应代理
         */
        public HttpServletResponse proxy() {
            return proxy;
        }

        /**
         * @return 快照
         */
        public Response snapshot() {
            return snapshot;
        }
    }

    /**
     * 从查询串里取一个参数。
     *
     * <p>只做 {@code k=v&k=v} 这一种常见形态：测试要验的是「服务端读到的是不是这个值」，
     * 不是把 Servlet 容器的解析器重写一遍。
     *
     * @param queryString 查询串，可为 {@code null}
     * @param name        参数名
     * @return 参数值，没有则 {@code null}
     */
    private static String queryParam(String queryString, String name) {
        if (queryString == null || queryString.isEmpty()) {
            return null;
        }
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!key.equals(name)) {
                continue;
            }
            String raw = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return raw;
            }
        }
        return null;
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }

    private static final class FakeInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        FakeInputStream(byte[] data) {
            this.delegate = new ByteArrayInputStream(data);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return delegate.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // 同步测试用不到
        }
    }

    private static final class FakeOutputStream extends ServletOutputStream {

        private final ByteArrayOutputStream delegate;

        FakeOutputStream(ByteArrayOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            delegate.write(b, off, len);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            // 同步测试用不到
        }
    }

    /**
     * 小工具：把 JSON 文本里的某个字符串字段取出来（测试里避免再写一遍解析）。
     *
     * @param json  响应体
     * @param field 字段名
     * @return 值，取不到返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public static String field(String json, String field) {
        Object parsed = com.harlanking.harken.web.json.Json.parse(json);
        if (!(parsed instanceof Map)) {
            return null;
        }
        Object v = ((Map<String, Object>) parsed).get(field);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * @param text 任意文本
     * @return 去掉空白并按小写归一，便于断言
     */
    public static String normalize(String text) {
        return text == null ? null : text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
