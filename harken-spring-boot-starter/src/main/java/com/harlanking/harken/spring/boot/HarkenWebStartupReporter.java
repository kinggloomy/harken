package com.harlanking.harken.spring.boot;

import com.harlanking.harken.web.HarkenWeb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.List;

/**
 * 启动时打印 HTTP 端点的摘要。
 *
 * <h2>这个报告器存在的理由</h2>
 * <p>「加一个依赖就多出一个管理端口」这件事必须<b>自己说出来</b>。
 * 静默挂载端点是安全工具最不该有的行为：装机的人不知道它开了什么，
 * 审计的人不知道它在哪里，出事时不知道该关哪里。
 *
 * <p>所以这个报告器把 URL、白名单、账号数、AI 开关一次性打在启动日志里，
 * 并且对两个「危险的宽松配置」直接 WARN：
 * <ul>
 *   <li>{@code allowed-ips} 里出现 {@code *} —— 任何能连上端口的人都能访问</li>
 *   <li>{@code trust-forwarded-for=true} —— 客户端可以伪造来源地址</li>
 * </ul>
 *
 * @author harlan
 * @since 1.0.0
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class HarkenWebStartupReporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HarkenWebStartupReporter.class);

    private final HarkenWeb web;
    private final HarkenProperties properties;

    /**
     * @param web        HTTP 层
     * @param properties 配置
     */
    public HarkenWebStartupReporter(HarkenWeb web, HarkenProperties properties) {
        this.web = web;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> lines = web.describe();
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator()).append("Harken HTTP endpoints").append(System.lineSeparator());
        for (String line : lines) {
            sb.append("  ").append(line).append(System.lineSeparator());
        }
        log.info(sb.toString());

        if (properties.getWeb().getAllowedIps().stream().anyMatch("*"::equals)) {
            log.warn("Harken: allowed-ips 含 '*'，任何能连上端口的客户端都可以访问控制台。"
                    + "只应在已有外层网关做认证的情况下使用。");
        }
        if (properties.getWeb().isTrustForwardedFor()) {
            log.warn("Harken: trust-forwarded-for=true，来源地址取自 X-Forwarded-For，该头可被伪造。"
                    + "仅当前置代理会覆盖这个头时才是安全的。");
        }
        if (web.auth().userCount() == 0) {
            log.warn("Harken: 当前没有可登录账号，人类通道不可用。");
        }
        if (!web.aiEnabled()) {
            log.info("Harken: AI 通道未启用（未配置 harken.ai.token），{}/ai/** 不存在。",
                    web.basePath());
        } else {
            // 端点存在但没人知道怎么用，等于不存在。把两份随包文档的位置说清楚：
            // 它们在 starter jar 里，用 jar -xf 取出来即可。
            log.info("Harken: AI 用法见 jar 内 harken/AGENTS.md 与 harken/harken-query.sh，"
                    + "取出方式：jar -xf harken-spring-boot-starter-<version>.jar harken/");
        }
    }
}
