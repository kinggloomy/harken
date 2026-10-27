package com.harlanking.demo.lib;

import com.harlanking.harken.core.HarkenOptions;
import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.catalog.FileScriptCatalog;
import com.harlanking.harken.core.catalog.RiskLevel;
import com.harlanking.harken.core.metadata.ClassIndex;
import com.harlanking.harken.core.spi.TransactionBridge;
import com.harlanking.harken.core.spi.WriteGuard;
import com.harlanking.harken.jdbc.GuardPolicy;
import com.harlanking.harken.jdbc.GuardedDataSource;
import com.harlanking.harken.jdbc.JdbcWriteGuard;
import com.harlanking.harken.web.HarkenWeb;
import com.harlanking.harken.web.HarkenWebConfig;
import com.harlanking.harken.web.WebMode;
import com.harlanking.harken.web.servlet.AuthServlet;
import com.harlanking.harken.web.servlet.HumanApiServlet;
import com.harlanking.harken.web.servlet.IpAllowlistFilter;
import com.harlanking.harken.web.servlet.UiServlet;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

import javax.sql.DataSource;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Library 形态验收：没有任何 Spring，纯 Java {@code main} 把 Harken 跑起来。
 *
 * <p>关键步骤（和 Spring Boot starter 自动配置做同一件事，只是手写）：
 * <ol>
 *   <li>用 {@link com.harlanking.harken.core.HarkenRuntime#builder()} 组装
 *       {@link HarkenService}，目录用 {@link FileScriptCatalog}（外部文件，支持热加载）；</li>
 *   <li>用 {@link HarkenWebConfig} 声明「受限模式 + 最高危险等级 WRITE」；</li>
 *   <li>用 {@link HarkenWeb#create} 拿到一组 servlet / filter；</li>
 *   <li>自己 new 一个嵌入式 Tomcat，把这些 servlet 按 starter 同样的路径挂上去。</li>
 * </ol>
 *
 * <p>事务桥是手写的一个 {@link JdbcTransactionBridge}：包一层内存 H2 连接，
 * 按 {@link TransactionBridge.Mode} 决定只读 / 回滚 / 提交。core 不认识 Spring，
 * 只认识这个接口，所以非 Spring 应用也能有「能回滚」的能力。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class LibraryDemoMain {

    /** 控制台基路径，与 starter 默认一致。 */
    private static final String BASE = "/harken";

    private LibraryDemoMain() {
    }

    /**
     * 启动。
     *
     * @param args {@code [脚本目录] [端口] [额外允许的IP,逗号分隔]}，都缺省时用
     *             {@code scripts} 与 {@code 8091}（白名单默认只放本机回环）
     */
    public static void main(String[] args) throws Exception {
        // Windows 控制台默认 GBK，中文日志会变乱码
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        String scriptsDir = args.length > 0 ? args[0] : "scripts";
        int port = args.length > 1 ? Integer.parseInt(args[1].trim()) : 8091;

        // 默认只放本机回环；要跨机打开演示页时，把来源 IP 作为第三个参数传进来
        List<String> allowIps = new ArrayList<>(List.of("127.0.0.1", "::1", "::ffff:127.0.0.1", "0:0:0:0:0:0:0:1"));
        if (args.length > 2) {
            for (String ip : args[2].split(",")) {
                if (!ip.isBlank() && !allowIps.contains(ip.trim())) {
                    allowIps.add(ip.trim());
                }
            }
        }

        HarkenService service = buildService(scriptsDir);
        HarkenWebConfig config = HarkenWebConfig.builder()
                .basePath(BASE)
                .allowedIps(allowIps)
                .environment("lib-demo")
                .mode(WebMode.RESTRICTED)
                .maxRiskForRestricted(RiskLevel.WRITE)
                .users(List.of(new HarkenWebConfig.UserSpec("admin", "lib-admin-pw", "ADMIN")))
                .build();

        HarkenWeb web = HarkenWeb.create(service, ClassIndex.empty(), config);

        Tomcat tomcat = new Tomcat();
        Path work = Files.createTempDirectory("harken-lib");
        tomcat.setBaseDir(work.toString());
        tomcat.setPort(port);
        tomcat.getConnector(); // 触发默认 HTTP 连接器创建

        Context ctx = tomcat.addContext("", work.toString());
        ctx.setConfigured(true);

        // IP 白名单过滤器：与 starter 一样挂 /harken 和 /harken/* 两条。
        //
        // 这里必须用 Tomcat 的 FilterDef/FilterMap，而不是标准 ServletContext.addFilter：
        // addFilter 只允许在上下文「启动过程中」（STARTING_PREP）调用，我们此刻还是 NEW，
        // 会抛 "Filters cannot be added to context [] as the context has been initialised"。
        // FilterDef/FilterMap 则必须在 start() 之前挂上去，正是当前时机。
        StandardContext sctx = (StandardContext) ctx;
        FilterDef ipDef = new FilterDef();
        ipDef.setFilterName("harkenIp");
        ipDef.setFilterClass(IpAllowlistFilter.class.getName());
        ipDef.setFilter(web.ipFilter());
        sctx.addFilterDef(ipDef);

        FilterMap ipMap = new FilterMap();
        ipMap.setFilterName("harkenIp");
        ipMap.addURLPattern(BASE);
        ipMap.addURLPattern(BASE + "/*");
        sctx.addFilterMap(ipMap);

        // 控制台页面 + 认证 + 人类通道（AI 未配置 Token，不挂）
        Wrapper ui = Tomcat.addServlet(ctx, "harkenUi", web.uiServlet());
        ui.addMapping(BASE);
        ui.addMapping(BASE + "/*");
        ui.setLoadOnStartup(1);

        Wrapper auth = Tomcat.addServlet(ctx, "harkenAuth", web.authServlet());
        auth.addMapping(BASE + "/auth/*");

        Wrapper human = Tomcat.addServlet(ctx, "harkenHuman", web.humanServlet());
        human.addMapping(BASE + "/api/*");

        System.out.println("Harken Library 形态已启动（受限模式）：");
        System.out.println("  控制台  : http://127.0.0.1:" + port + BASE + "/");
        System.out.println("  账号    : admin / lib-admin-pw");
        System.out.println("  脚本目录: " + scriptsDir + " （FileScriptCatalog，支持热加载）");
        System.out.println("  ──────────────────────────────────────");
        web.describe().forEach(System.out::println);

        tomcat.start();
        tomcat.getServer().await();
    }

    /**
     * 组装核心服务：目录 + 真实事务 + JDBC 写入守卫。
     *
     * @param scriptsDir 外部脚本目录
     * @return 可直接使用的服务
     */
    private static HarkenService buildService(String scriptsDir) {
        DataSource raw = newH2();
        JdbcWriteGuard writeGuard = new JdbcWriteGuard(new GuardPolicy(0, List.of(), false));
        // GuardedDataSource 没有 public 构造器，用静态 wrap 包一层代理
        DataSource ds = GuardedDataSource.wrap(raw, writeGuard);
        TransactionBridge tx = new JdbcTransactionBridge(ds);

        return com.harlanking.harken.core.HarkenRuntime.builder()
                .catalog(new FileScriptCatalog(scriptsDir))
                .transactionBridge(tx)
                .writeGuard(writeGuard)
                .options(HarkenOptions.defaults())
                .build();
    }

    private static DataSource newH2() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:harkenlib;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * 手写的事务桥：core 只认这个接口，不认 Spring。
     *
     * <p>一个连接包住一次执行，按模式决定只读 / 回滚 / 提交。演示脚本不真正碰库，
     * 所以这里的事务主要用来证明「受限模式也有完整的回滚/提交语义」。
     */
    static final class JdbcTransactionBridge implements TransactionBridge {

        private final DataSource ds;

        JdbcTransactionBridge(DataSource ds) {
            this.ds = Objects.requireNonNull(ds, "dataSource");
        }

        @Override
        public <T> T inTransaction(Mode mode, Supplier<T> work) {
            try (Connection c = ds.getConnection()) {
                if (mode == Mode.READ_ONLY) {
                    c.setReadOnly(true);
                }
                c.setAutoCommit(false);
                try {
                    T result = work.get();
                    if (mode == Mode.COMMIT) {
                        c.commit();
                    } else {
                        c.rollback();
                    }
                    return result;
                } catch (RuntimeException | Error e) {
                    try { c.rollback(); } catch (SQLException ignore) { /* 回滚失败也无能为力 */ }
                    throw e;
                } finally {
                    try { c.setAutoCommit(true); } catch (SQLException ignore) { /* 还原连接状态 */ }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("事务失败：" + e.getMessage(), e);
            }
        }

        @Override
        public String description() {
            return "jdbc (H2 in-memory)";
        }
    }
}
