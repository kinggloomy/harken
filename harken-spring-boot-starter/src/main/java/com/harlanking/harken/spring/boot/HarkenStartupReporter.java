package com.harlanking.harken.spring.boot;

import com.harlanking.harken.core.ConfigFilePermissions;
import com.harlanking.harken.core.HarkenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.List;

/**
 * 启动时打印一行状态的报告器。
 *
 * <h2>为什么值得单独一个类</h2>
 * 这个工具最危险的失败方式不是报错，而是「静默地没有保护」：
 * 应用启动了、脚本能跑、但根本没有事务管理器，用户以为改动会回滚，
 * 实际已经落库。所以启动时必须在日志里<b>明确写出</b>三件事：
 * 事务能力、审计落点、暴露范围。
 *
 * <p>只在 {@code ApplicationRunner} 阶段执行，因此单元测试不会触发；
 * {@code harken.print-banner=false} 可以关掉。
 *
 * <p>banner 保持纯 ASCII —— Windows 的 GBK 控制台会把中文打成乱码，
 * 而启动信息恰恰是用户第一眼看到的东西。
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)}：状态必须排在业务自己的
 * {@code ApplicationRunner} 之前打印。否则使用者先看到自己脚本的输出、
 * 再看到「其实没有事务保护」的说明，顺序是反的。
 *
 * @author harlan
 * @since 1.0.0
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HarkenStartupReporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HarkenStartupReporter.class);

    private static final String LINE =
            "============================================================";

    private final HarkenProperties properties;
    private final HarkenService service;
    private final Environment environment;

    /**
     * @param properties  配置
     * @param service     编排器
     * @param environment Spring 环境，用于定位承载密码的配置文件
     */
    public HarkenStartupReporter(HarkenProperties properties, HarkenService service,
                                 Environment environment) {
        this.properties = properties;
        this.service = service;
        this.environment = environment;
    }

    /**
     * 打印 banner 与必要警告。异常不向外抛：启动信息失败不该让应用起不来。
     *
     * @param args 启动参数，未使用
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            if (properties.isPrintBanner()) {
                log.info("\n{}\n Harken {} - runtime console. Listen closer.\n"
                                + " transaction : {}\n"
                                + " write guard : {}\n"
                                + " audit       : {}\n"
                                + " packages    : {}\n"
                                + " console     : {}\n{}",
                        LINE, version(), service.transactionDescription(),
                        service.writeGuardDescription(), service.auditDescription(),
                        packagesText(), consoleText(), LINE);
            }
            warnIfUnprotected();
            warnIfConfigFileTooOpen();
        } catch (RuntimeException e) {
            log.debug("Harken 启动报告失败", e);
        }
    }

    /**
     * 三级提示的第一级：启动 WARN。
     *
     * <p>另两级分别是 UI 常驻红色横幅、审计记录里的 {@code transactionProtected=false}。
     */
    private void warnIfUnprotected() {
        if (!service.transactionSupported()) {
            log.warn("Harken: 未检测到事务管理器。commit=true 的写入与 commit=false 的试跑"
                    + "都会被拒绝并返回 HARKEN_NO_TRANSACTION —— 没有事务就无法保证改动不落库，"
                    + "此时报「已回滚」是假话。AI 只读通道默认同样拒绝"
                    + "（harken.require-transaction-for-ai=false 可显式承担风险）。"
                    + "如需使用，请在容器中提供 PlatformTransactionManager。");
        }
        List<String> packages = properties.getPackages();
        if (packages == null || packages.isEmpty()) {
            log.warn("Harken: harken.packages 未配置，脚本可访问全部非框架 Bean"
                    + "（含数据源、连接池、模板引擎）。建议限制到业务包，例如 harken.packages=com.zm");
        }
        if (properties.getGuard().isEnabled() && properties.toGuardPolicy().nothingToEnforce()) {
            log.warn("Harken: 写入守卫未配置策略（harken.guard.max-affected-rows / deny-tables），"
                    + "脚本可以一次修改任意多行、也可写任何表。事务能保证「不提交就回滚」，"
                    + "但拦不住「提交之后才发现改了八万行」。建议至少配一个行数上限。");
        }
    }

    private String packagesText() {
        List<String> packages = properties.getPackages();
        return packages == null || packages.isEmpty() ? "(all non-framework beans)" : String.join(", ", packages);
    }

    /**
     * 控制台入口的一行说明。纯 ASCII，且不写死 {@code /harken} —— 基路径可配。
     *
     * @return 例如 {@code /harken/api/** [human]  /harken/ai/** [ai]}
     */
    private String consoleText() {
        HarkenProperties.Web web = properties.getWeb();
        if (!web.isEnabled()) {
            return "disabled (harken.web.enabled=false)";
        }
        String base = web.getBasePath();
        boolean ai = properties.getAi().isEnabled()
                && properties.getAi().getToken() != null
                && !properties.getAi().getToken().isBlank();
        return base + "/api/** [human]" + (ai ? "  " + base + "/ai/** [ai]" : "  (ai channel not registered)");
    }

    /**
     * 配置文件权限检查。
     *
     * <p>密码（哈希或明文）与 AI Token 都在配置里。文件若是 {@code 644}，
     * 同机任何用户都能读走 —— 应用本身没有漏洞，凭据却已经泄露。
     * 这里只 WARN，不拒绝启动：Kubernetes Secret、镜像 umask 等情况 Harken 看不到，
     * 强行失败会造成比泄漏更常见的发布事故。
     *
     * <p>Windows 无 POSIX 模型时 {@code ConfigFilePermissions} 返回
     * {@code UNSUPPORTED}，此时保持沉默 —— 不能假装检查过。
     */
    private void warnIfConfigFileTooOpen() {
        List<ConfigFilePermissions.Result> results = HarkenConfigFileCheck.check(environment);
        if (log.isDebugEnabled()) {
            for (ConfigFilePermissions.Result result : results) {
                log.debug("Harken: 配置来源 {} -> {} ({})",
                        result.path(), result.status(), result.detail());
            }
        }
        for (ConfigFilePermissions.Result result : results) {
            if (!result.shouldWarn()) {
                continue;
            }
            Path path = result.path();
            log.warn("Harken: 配置文件 {} 未受保护（{}）—— 同机其他用户可能读到里面的账号密码与 AI Token，"
                            + "建议执行 chmod 600 {}",
                    path, result.detail(), path);
        }
    }

    private static String version() {
        String version = HarkenService.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
