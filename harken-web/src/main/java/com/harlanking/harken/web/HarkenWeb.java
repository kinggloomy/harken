package com.harlanking.harken.web;

import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.metadata.ClassIndex;
import com.harlanking.harken.core.metadata.MethodCatalog;
import com.harlanking.harken.web.auth.AuthService;
import com.harlanking.harken.web.servlet.AiApiServlet;
import com.harlanking.harken.web.servlet.AuthServlet;
import com.harlanking.harken.web.servlet.HumanApiServlet;
import com.harlanking.harken.web.servlet.IpAllowlistFilter;
import com.harlanking.harken.web.servlet.UiServlet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * HTTP 层的组装结果。一个对象把「该挂哪些 servlet / filter」说清楚，
 * 至于挂到哪个容器上（Spring Boot 的 {@code ServletRegistrationBean}、
 * 裸 Tomcat 的 {@code Tomcat.addServlet}、外置 web.xml），由调用方决定。
 *
 * <h2>为什么不是「一个 Servlet 处理所有路径」</h2>
 * <p>一个 servlet 意味着「AI 与人类共用一个入口，靠路径前缀分流」。
 * 那种写法下，只要有一行判断写错，AI 通道就能拿到人类能力。
 * 拆成两个 servlet 之后，AI 那个类<b>没有</b> {@code ReadWriteRequest} 这个符号，
 * 想写出漏洞得先改类结构 —— 而不是改一行 if。
 *
 * <h2>两种使用方式</h2>
 * <pre>{@code
 * // Spring Boot：由 starter 自动完成，见 HarkenWebAutoConfiguration
 * // 非 Spring：自己注册
 * HarkenWeb web = HarkenWeb.create(service, ClassIndex.empty(), config);
 * context.addServlet("harkenApi", web.humanServlet()).addMapping(web.basePath() + "/api/*");
 * context.addFilter("harkenIp", web.ipFilter()).addMappingForUrlPatterns(null, false, web.basePath() + "/*");
 * }</pre>
 *
 * @author harlan
 * @since 1.0.0
 */
public final class HarkenWeb {

    private final HarkenService service;
    private final HarkenWebConfig config;
    private final AuthService auth;
    private final IpAllowlistFilter ipFilter;
    private final AuthServlet authServlet;
    private final HumanApiServlet humanServlet;
    private final AiApiServlet aiServlet;
    private final UiServlet uiServlet;

    private HarkenWeb(HarkenService service, ClassIndex classIndex, HarkenWebConfig config) {
        this.service = Objects.requireNonNull(service, "service");
        this.config = Objects.requireNonNull(config, "config");
        this.auth = new AuthService(config);
        this.ipFilter = new IpAllowlistFilter(IpAllowList.parse(config.allowedIps()),
                config.trustForwardedFor());
        this.authServlet = new AuthServlet(auth, config);
        // 方法表复用编排器的 Bean 解析器：容器里只该有一份「Bean 名 → 类型」的扫描结果
        MethodCatalog catalog = new MethodCatalog(service.beanResolver());
        this.humanServlet = new HumanApiServlet(service, classIndex, catalog, auth, config);
        this.aiServlet = new AiApiServlet(service, classIndex, catalog, auth, config);
        this.uiServlet = new UiServlet();
    }

    /**
     * 组装 HTTP 层。
     *
     * @param service    执行编排器
     * @param classIndex 业务类名索引；非 Spring 场景传 {@link ClassIndex#empty()}
     * @param config     HTTP 配置
     * @return 组装结果
     */
    public static HarkenWeb create(HarkenService service, ClassIndex classIndex,
                                   HarkenWebConfig config) {
        return new HarkenWeb(service, classIndex == null ? ClassIndex.empty() : classIndex, config);
    }

    /**
     * @return 基路径，例如 {@code /harken}
     */
    public String basePath() {
        return config.basePath();
    }

    /**
     * AI 通道是否已启用。
     *
     * @return 配置了 Token 才返回 {@code true}；返回 {@code false} 时不应注册 AI servlet
     */
    public boolean aiEnabled() {
        return auth.aiEnabled();
    }

    /** @return 认证端点 servlet */
    public AuthServlet authServlet() {
        return authServlet;
    }

    /** @return 人类通道 servlet */
    public HumanApiServlet humanServlet() {
        return humanServlet;
    }

    /** @return AI 通道 servlet */
    public AiApiServlet aiServlet() {
        return aiServlet;
    }

    /** @return 控制台页面 servlet */
    public UiServlet uiServlet() {
        return uiServlet;
    }

    /** @return IP 白名单过滤器 */
    public IpAllowlistFilter ipFilter() {
        return ipFilter;
    }

    /** @return 认证中心，健康检查与测试会用到 */
    public AuthService auth() {
        return auth;
    }

    /** @return 生效的配置 */
    public HarkenWebConfig config() {
        return config;
    }

    /**
     * 启动摘要，供启动日志打印。
     *
     * <p>刻意做成多行文本而不是一行：这些信息是运维人员第一次接触这个工具时
     * <b>唯一</b>能看的东西，「URL 是什么、密码从哪来、AI 开没开」必须一眼看到。
     *
     * @return 若干行摘要
     */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("console        : " + config.basePath() + "/");
        lines.add("environment    : " + (config.environment().isEmpty()
                ? "(unset - set harken.web.environment)" : config.environment()));
        lines.add("base path      : " + config.basePath());
        lines.add("allowed ips    : " + (config.allowedIps().isEmpty()
                ? "(empty = deny all)" : String.join(", ", config.allowedIps())));
        lines.add("human api      : POST " + config.basePath() + "/api/execute");
        lines.add("login          : POST " + config.basePath() + "/auth/login");
        lines.add("session        : idle " + config.sessionIdle().toMinutes() + "m, max "
                + config.sessionMax().toHours() + "h");
        lines.add("login lockout  : " + config.maxLoginFailures() + " failures / "
                + config.lockDuration().toMinutes() + "m");
        lines.add("users          : " + auth.userCount() + " account(s)");
        lines.add("ai channel     : " + (auth.aiEnabled()
                ? "enabled (POST " + config.basePath() + "/ai/query)"
                : "disabled (set harken.ai.token to enable)"));
        // 签名状态必须单独一行：它决定「调用方要不要改代码」，藏在别的行里会让人以为
        // 「AI 通了就万事大吉」，然后在第一次 401 上浪费半小时
        if (auth.aiEnabled()) {
            lines.add("ai signature   : " + (auth.signatureVerifier().enabled()
                    ? "required (window " + auth.signatureVerifier().windowSeconds() + "s)"
                    : "off (set harken.ai.signing-secret to require HMAC)"));
        }
        return lines;
    }
}
