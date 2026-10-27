package com.harlanking.harken.spring.boot;

import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.catalog.ClasspathScriptCatalog;
import com.harlanking.harken.core.catalog.FileScriptCatalog;
import com.harlanking.harken.core.catalog.NoopScriptCatalog;
import com.harlanking.harken.core.catalog.ScriptCatalog;
import com.harlanking.harken.core.impl.MemoryAuditStore;
import com.harlanking.harken.core.impl.Slf4jAuditStore;
import com.harlanking.harken.core.metadata.ClassIndex;
import com.harlanking.harken.core.spi.WriteGuard;
import com.harlanking.harken.jdbc.JdbcAuditStore;
import com.harlanking.harken.jdbc.JdbcWriteGuard;
import com.harlanking.harken.spring.SpringHarken;
import groovy.lang.GroovyShell;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/**
 * 自动配置：加一个依赖，{@link HarkenService} 就出现在容器里。
 *
 * <h2>两个条件注解的作用</h2>
 * <ul>
 *   <li>{@code @ConditionalOnClass(GroovyShell)} —— 使用者没引 Groovy 时整个配置不生效。
 *       Groovy 有 6~7MB，不该强加给不用这个功能的应用。</li>
 *   <li>{@code @ConditionalOnProperty(harken.enabled)} —— 可以整体关闭。
 *       默认 {@code true}（开箱即用），但网络层默认只绑回环地址，
 *       真正的安全边界不靠这个开关。</li>
 * </ul>
 *
 * <h2>三个 Bean 都是 {@code @ConditionalOnMissingBean}</h2>
 * 使用者自己声明同类型 Bean 就完全接管，不会被自动配置覆盖。这是 Spring Boot 的
 * 标准礼仪，也让「想深度定制」的人不用排除自动配置类。
 *
 * <h2>装配时会实例化扩展点 Bean</h2>
 * <p>{@link SpringHarken#service} 内部用 {@code getBeansOfType} 收集
 * {@code BindingContributor} / {@code ScriptValidator} / {@code AuditStore}。
 * 这会提前初始化这几类 Bean（它们本来就该是轻量单例）。
 * <b>不要让扩展点 Bean 反向依赖 {@link HarkenService}</b>，否则会出现循环依赖。
 *
 * @author harlan
 * @since 1.0.0
 */
@AutoConfiguration
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnClass({ApplicationContext.class, GroovyShell.class})
@ConditionalOnProperty(prefix = "harken", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HarkenProperties.class)
public class HarkenAutoConfiguration {

    /**
     * 本类的无参构造器，供 Spring 实例化。
     */
    public HarkenAutoConfiguration() {
    }

    /**
     * 编排器。所有执行入口（HTTP、AI、受限模式）都从这里拿。
     *
     * @param context    Spring 容器
     * @param properties 配置
     * @param writeGuard 写入守卫（没配策略时是一个什么都不做的实现）
     * @return 编排器
     */
    @Bean
    @ConditionalOnMissingBean
    public HarkenService harkenService(ApplicationContext context, HarkenProperties properties,
                                       WriteGuard writeGuard, ObjectProvider<ScriptCatalog> catalogProvider) {
        ScriptCatalog catalog = catalogProvider.getIfAvailable();
        return SpringHarken.service(context, properties.getPackages(), properties.toOptions(),
                writeGuard, catalog == null ? NoopScriptCatalog.INSTANCE : catalog);
    }

    /**
     * 预置脚本目录。用户自己声明 {@link ScriptCatalog} Bean 就完全接管
     * （{@code @ConditionalOnMissingBean}）。
     *
     * <p>按 {@code harken.catalog.provider} 决定来源：
     * <ul>
     *   <li>{@code classpath} —— jar 内 {@code harken-scripts/*.json}</li>
     *   <li>{@code file} —— 外部文件/目录，必须配 {@code harken.catalog.file}</li>
     *   <li>{@code none}（默认）—— 空目录</li>
     * </ul>
     *
     * @param properties 配置
     * @return 目录
     */
    @Bean
    @ConditionalOnMissingBean(ScriptCatalog.class)
    public ScriptCatalog harkenScriptCatalog(HarkenProperties properties) {
        String provider = properties.getCatalog().getProvider();
        if ("classpath".equalsIgnoreCase(provider)) {
            return new ClasspathScriptCatalog();
        }
        if ("file".equalsIgnoreCase(provider)) {
            String file = properties.getCatalog().getFile();
            if (file == null || file.isBlank()) {
                throw new IllegalStateException("harken.catalog.provider=file 但未配置 harken.catalog.file");
            }
            return new FileScriptCatalog(file);
        }
        return NoopScriptCatalog.INSTANCE;
    }

    /**
     * 写入守卫。没配策略时它什么都不做，但仍会作为 Bean 存在，
     * 这样「启动日志里有没有行数闸」这件事永远有一个确定的答案。
     *
     * <p>使用者自己声明 {@link WriteGuard} Bean 即完全接管。
     *
     * @param properties 配置
     * @return 守卫
     */
    @Bean
    @ConditionalOnMissingBean
    public JdbcWriteGuard harkenWriteGuard(HarkenProperties properties) {
        return new JdbcWriteGuard(properties.toGuardPolicy());
    }

    /**
     * 数据源包装器。声明成 {@code static} 是为了不在 BeanPostProcessor 注册阶段
     * 提前实例化本配置类，那会连带触发属性绑定时序问题。
     *
     * @param guardProvider 守卫提供者，延迟解析
     * @return BeanPostProcessor
     */
    @Bean
    @ConditionalOnMissingBean(name = "harkenDataSourceGuardPostProcessor")
    public static BeanPostProcessor harkenDataSourceGuardPostProcessor(
            ObjectProvider<WriteGuard> guardProvider) {
        return new HarkenDataSourceGuardPostProcessor(guardProvider);
    }

    /**
     * 数据库审计。开启后审计同时写日志与表：日志给运维看，表给合规查。
     *
     * <p>只在存在 {@link DataSource} 且显式开启时创建 —— 默认的日志审计零建表、零权限要求，
     * 适合大多数场景。
     *
     * @param dataSource 数据源（包装过的会被自动拆开）
     * @param properties 配置
     * @return 审计存储
     */
    @Bean
    @ConditionalOnClass(DataSource.class)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "harken.audit.jdbc", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(JdbcAuditStore.class)
    public JdbcAuditStore harkenJdbcAuditStore(DataSource dataSource, HarkenProperties properties) {
        HarkenProperties.Audit.Jdbc jdbc = properties.getAudit().getJdbc();
        return new JdbcAuditStore(dataSource, jdbc.getTable(), jdbc.isAutoCreate());
    }

    /**
     * 数据库审计开启时，日志审计也保留一份。
     *
     * <p>只有 {@code audit.jdbc.enabled=true} 时才注册：不开启时行为与之前完全一致
     * （没有 {@code AuditStore} Bean，由 {@code HarkenService} 兜底用 {@code Slf4jAuditStore}）。
     *
     * @return 日志审计
     */
    @Bean
    @ConditionalOnProperty(prefix = "harken.audit.jdbc", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(Slf4jAuditStore.class)
    public Slf4jAuditStore harkenSlf4jAuditStore() {
        return new Slf4jAuditStore();
    }

    /**
     * 内存审计（环形缓冲）。默认开启，是<b>多一条通道</b>，日志审计照旧。
     *
     * <p>给控制台的「最近执行」面板用：能 {@code query}、零建表、零权限要求。
     * 重启即失、多实例各一份 —— 合规场景请用 {@code audit.jdbc}。
     *
     * @param properties 配置
     * @return 内存审计存储
     */
    @Bean
    @ConditionalOnProperty(prefix = "harken.audit.memory", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(MemoryAuditStore.class)
    public MemoryAuditStore harkenMemoryAuditStore(HarkenProperties properties) {
        return new MemoryAuditStore(properties.getAudit().getMemory().getCapacity());
    }

    /**
     * 内存审计开启时，日志审计也保留一份。
     *
     * <p>内存不能当证据：进程一停就没了。所以只要挂了额外通道，就把默认的日志审计补上，
     * 避免「以为开了审计，其实只存在内存里」。
     *
     * <p>条件里带上 {@code harkenJdbcAuditStore} 不存在这条，是为了和上面的数据库审计版本
     * 互斥 —— 两个方法都靠 {@code @ConditionalOnMissingBean} 兜底时，谁先评估谁后评估并不确定，
     * 很容易注册出两个 {@code Slf4jAuditStore}，审计 banner 上就会出现两遍。
     *
     * @return 日志审计
     */
    @Bean
    @ConditionalOnProperty(prefix = "harken.audit.memory", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(value = Slf4jAuditStore.class, name = "harkenJdbcAuditStore")
    public Slf4jAuditStore harkenSlf4jAuditStoreForMemory() {
        return new Slf4jAuditStore();
    }

    /**
     * 业务类名索引，供 {@code import} 补全使用。
     *
     * <p>构建只枚举 {@code .class} 的路径、不加载类，百级类在毫秒以内，所以直接在启动时建好 ——
     * 不需要懒加载，也就没有「索引过期」这个问题。方法表仍然按需读，
     * 见 {@link com.harlanking.harken.core.metadata.MethodCatalog}。
     *
     * @param context    Spring 容器
     * @param properties 配置
     * @return 索引，永不返回 {@code null}
     */
    @Bean
    @ConditionalOnMissingBean
    public ClassIndex harkenClassIndex(ApplicationContext context, HarkenProperties properties) {
        return SpringHarken.classIndex(context, properties.getPackages());
    }

    /**
     * 启动报告：打印 banner，并在没有事务保护时给出 WARN。
     *
     * @param properties  配置
     * @param service     编排器
     * @param environment Spring 环境，用来定位承载密码的配置文件
     * @return 报告器
     */
    @Bean
    @ConditionalOnMissingBean
    public HarkenStartupReporter harkenStartupReporter(HarkenProperties properties, HarkenService service,
                                                       Environment environment) {
        return new HarkenStartupReporter(properties, service, environment);
    }
}
