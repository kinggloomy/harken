package com.harlanking.harken.spring.boot;

import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.metadata.ClassIndex;
import com.harlanking.harken.web.HarkenWeb;
import com.harlanking.harken.web.servlet.AiApiServlet;
import com.harlanking.harken.web.servlet.AuthServlet;
import com.harlanking.harken.web.servlet.HumanApiServlet;
import com.harlanking.harken.web.servlet.IpAllowlistFilter;
import com.harlanking.harken.web.servlet.UiServlet;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;

/**
 * 把 {@link HarkenWeb} 挂到 Spring Boot 的 Servlet 容器上。
 *
 * <h2>注册了五个东西</h2>
 * <table border="1">
 *   <caption>注册项</caption>
 *   <tr><th>类型</th><th>路径</th><th>何时注册</th></tr>
 *   <tr><td>{@link IpAllowlistFilter}</td><td>{@code /harken} 与 {@code /harken/*}</td><td>总是</td></tr>
 *   <tr><td>{@link UiServlet}</td><td>{@code /harken} 与 {@code /harken/*}</td><td>总是</td></tr>
 *   <tr><td>{@link AuthServlet}</td><td>{@code /harken/auth/*}</td><td>总是</td></tr>
 *   <tr><td>{@link HumanApiServlet}</td><td>{@code /harken/api/*}</td><td>总是</td></tr>
 *   <tr><td>{@link AiApiServlet}</td><td>{@code /harken/ai/*}</td><td><b>仅当配置了 AI Token</b></td></tr>
 * </table>
 *
 * <h2>为什么过滤器用 FilterRegistrationBean 而不是 @WebFilter</h2>
 * <p>{@code @WebFilter} 依赖 {@code @ServletComponentScan}，会强制使用者扫自己的包。
 * {@code FilterRegistrationBean} 是 Boot 的标准做法，顺序、路径都能精确控制，
 * 而且不污染使用者的组件扫描范围。
 *
 * <h2>条件链</h2>
 * <ol>
 *   <li>有 Servlet API —— 纯非 Web 应用（batch、CLI）不受影响</li>
 *   <li>是 Servlet Web 应用 —— Reactive 应用不适用，别硬塞</li>
 *   <li>{@code harken.web.enabled=true}（默认）</li>
 *   <li>容器里已有 {@link HarkenService}（由上一条自动配置提供）</li>
 * </ol>
 *
 * @author harlan
 * @since 1.0.0
 */
@AutoConfiguration(after = HarkenAutoConfiguration.class)
@ConditionalOnClass(name = {
        "jakarta.servlet.Servlet",
        "org.springframework.boot.web.servlet.ServletRegistrationBean"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "harken.web", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(HarkenService.class)
@EnableConfigurationProperties(HarkenProperties.class)
public class HarkenWebAutoConfiguration {

    /** 构造器。 */
    public HarkenWebAutoConfiguration() {
    }

    /**
     * HTTP 层组装。
     *
     * @param service    编排器
     * @param classIndex 业务类名索引（自动配置已提供）
     * @param properties 配置
     * @return 组装结果
     */
    @Bean
    @ConditionalOnMissingBean
    public HarkenWeb harkenWeb(HarkenService service, ClassIndex classIndex,
                               HarkenProperties properties) {
        return HarkenWeb.create(service, classIndex, properties.toWebConfig());
    }

    /**
     * IP 白名单过滤器，排在所有 Harken 端点之前。
     *
     * @param web HTTP 层
     * @return 注册项
     */
    @Bean
    public FilterRegistrationBean<IpAllowlistFilter> harkenIpFilter(HarkenWeb web) {
        FilterRegistrationBean<IpAllowlistFilter> bean = new FilterRegistrationBean<>(web.ipFilter());
        // 两个都要挂：`/harken/*` 不匹配 `/harken` 本身（Servlet 规范的路径映射语义）。
        // 只挂前者的话，`GET /harken` 会绕开 IP 白名单 —— 虽然不是数据接口，
        // 但「白名单之外的机器能看见控制台存在」这件事本身就是信息泄漏
        bean.addUrlPatterns(web.basePath() + "/*", web.basePath());
        bean.setName("harkenIpFilter");
        // 尽量靠前：越早拒绝，后面所有逻辑都不用执行
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }

    /**
     * 控制台页面。
     *
     * <p>同时映射 {@code /harken} 与 {@code /harken/*}：前者是使用者真正会敲的地址，
     * 后者挂了页面资源。`/harken/api/*` 这类更长的前缀由容器按「最长匹配」优先，
     * 不会落到这里。
     *
     * @param web HTTP 层
     * @return 注册项
     */
    @Bean
    public ServletRegistrationBean<UiServlet> harkenUiServlet(HarkenWeb web) {
        return servlet(web.uiServlet(), "harkenUiServlet", 0, web.basePath(), web.basePath() + "/*");
    }

    /**
     * 认证端点。
     *
     * @param web HTTP 层
     * @return 注册项
     */
    @Bean
    public ServletRegistrationBean<AuthServlet> harkenAuthServlet(HarkenWeb web) {
        return servlet(web.authServlet(), "harkenAuthServlet", 1, web.basePath() + "/auth/*");
    }

    /**
     * 人类通道。
     *
     * @param web HTTP 层
     * @return 注册项
     */
    @Bean
    public ServletRegistrationBean<HumanApiServlet> harkenHumanApiServlet(HarkenWeb web) {
        return servlet(web.humanServlet(), "harkenHumanApiServlet", 2, web.basePath() + "/api/*");
    }

    /**
     * AI 通道。没有 Token 时这个 Bean 根本不会被创建，端点也就不存在。
     *
     * @param web HTTP 层
     * @return 注册项
     */
    @Bean
    @Conditional(AiChannelEnabledCondition.class)
    public ServletRegistrationBean<AiApiServlet> harkenAiApiServlet(HarkenWeb web) {
        return servlet(web.aiServlet(), "harkenAiApiServlet", 3, web.basePath() + "/ai/*");
    }

    /**
     * 启动摘要，打印端点、白名单、账号数与 AI 开关。
     *
     * @param web        HTTP 层
     * @param properties 配置
     * @return 报告器
     */
    @Bean
    @ConditionalOnMissingBean
    public HarkenWebStartupReporter harkenWebStartupReporter(HarkenWeb web, HarkenProperties properties) {
        return new HarkenWebStartupReporter(web, properties);
    }

    private static <T extends jakarta.servlet.Servlet> ServletRegistrationBean<T> servlet(
            T instance, String name, int order, String... mappings) {
        ServletRegistrationBean<T> bean = new ServletRegistrationBean<>(instance, mappings);
        bean.setName(name);
        bean.setOrder(order);
        // 立刻初始化：配错了要在启动时就报错，而不是等第一个请求
        bean.setLoadOnStartup(1);
        return bean;
    }
}
