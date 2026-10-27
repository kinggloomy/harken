package com.harlanking.harken.spring;

import com.harlanking.harken.core.HarkenOptions;
import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.catalog.NoopScriptCatalog;
import com.harlanking.harken.core.catalog.ScriptCatalog;
import com.harlanking.harken.core.engine.GroovyScriptEngine;
import com.harlanking.harken.core.impl.DefaultScriptValidator;
import com.harlanking.harken.core.metadata.ClassIndex;
import com.harlanking.harken.core.spi.AuditStore;
import com.harlanking.harken.core.spi.BeanResolver;
import com.harlanking.harken.core.spi.BindingContributor;
import com.harlanking.harken.core.spi.ScriptValidator;
import com.harlanking.harken.core.spi.TransactionBridge;
import com.harlanking.harken.core.spi.WriteGuard;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Spring 场景的一行装配入口。starter 内部用的就是这几个方法。
 *
 * <h2>不是 Spring 用户，别用这个类</h2>
 * <p>非 Spring 项目请用 {@link com.harlanking.harken.core.HarkenRuntime#builder()}，
 * 那是纯 Java 的装配方式，不需要任何框架。
 *
 * <h2>自动收集的三类扩展点</h2>
 * <p>{@link #service} 会把容器里所有 {@link BindingContributor}、{@link ScriptValidator}、
 * {@link AuditStore} 的 Bean 收进来，按各自的 {@code getOrder()} 排序。
 * 用户只要给自己的实现加 {@code @Component} 就生效，零配置。
 *
 * <p>{@link DefaultScriptValidator} 总是被加入，无法通过容器移除 ——
 * 「禁止编译期执行特性」这条规则不允许被遗漏。
 *
 * <h2>事务桥的优先级</h2>
 * <ol>
 *   <li>容器里有唯一的 {@link TransactionBridge} Bean → 用它（用户完全接管）</li>
 *   <li>否则找唯一的 {@link org.springframework.transaction.PlatformTransactionManager}
 *       → 包成 {@link SpringTransactionBridge}</li>
 *   <li>都没有 → {@link TransactionBridge.NoopTransactionBridge}，写操作会被拒绝</li>
 * </ol>
 *
 * @author harlan
 * @since 1.0.0
 */
public final class SpringHarken {

    private SpringHarken() {
    }

    /**
     * 用默认参数装配：不限制业务包、10 秒超时。
     *
     * @param context Spring 容器
     * @return 可直接使用的编排器
     */
    public static HarkenService service(ApplicationContext context) {
        return service(context, List.of(), null);
    }

    /**
     * 装配编排器。
     *
     * @param context  Spring 容器
     * @param packages 业务包白名单，例如 {@code List.of("com.zm")}；空表示只排除框架包
     * @param options  执行参数，{@code null} 用默认值
     * @return 可直接使用的编排器
     */
    public static HarkenService service(ApplicationContext context,
                                        Collection<String> packages,
                                        HarkenOptions options) {
        return service(context, packages, options, WriteGuard.NONE);
    }

    /**
     * 装配编排器，并指定写入守卫。
     *
     * <p>{@code harken-spring-boot-starter} 走的是这个重载：守卫由
     * {@code harken-jdbc} 提供，Spring 层只负责按配置决定「装不装」。
     *
     * @param context    Spring 容器
     * @param packages   业务包白名单，空表示只排除框架包
     * @param options    执行参数，{@code null} 用默认值
     * @param writeGuard 写入守卫，{@code null} 等于不检查
     * @return 可直接使用的编排器
     */
    public static HarkenService service(ApplicationContext context,
                                        Collection<String> packages,
                                        HarkenOptions options,
                                        WriteGuard writeGuard) {
        BeanResolver resolver = new SpringBeanResolver(context, packages);

        List<BindingContributor> contributors = new ArrayList<>(
                context.getBeansOfType(BindingContributor.class).values());

        List<ScriptValidator> validators = new ArrayList<>();
        validators.add(new DefaultScriptValidator());
        validators.addAll(context.getBeansOfType(ScriptValidator.class).values());

        List<AuditStore> auditStores = new ArrayList<>(
                context.getBeansOfType(AuditStore.class).values());

        return new HarkenService(
                new GroovyScriptEngine(classLoaderOf(context), 256),
                resolver,
                contributors,
                validators,
                auditStores,
                SpringTransactionBridge.detect(context),
                options != null ? options : HarkenOptions.defaults(),
                writeGuard);
    }

    /**
     * 装配编排器，并指定预置脚本目录。
     *
     * <p>{@code harken-spring-boot-starter} 走这个重载：目录由配置决定（classpath / 文件 / 空）。
     *
     * @param context    Spring 容器
     * @param packages   业务包白名单，空表示只排除框架包
     * @param options    执行参数，{@code null} 用默认值
     * @param writeGuard 写入守卫，{@code null} 等于不检查
     * @param catalog    预置脚本目录，{@code null} 等于空目录
     * @return 可直接使用的编排器
     */
    public static HarkenService service(ApplicationContext context,
                                        Collection<String> packages,
                                        HarkenOptions options,
                                        WriteGuard writeGuard,
                                        ScriptCatalog catalog) {
        BeanResolver resolver = new SpringBeanResolver(context, packages);

        List<BindingContributor> contributors = new ArrayList<>(
                context.getBeansOfType(BindingContributor.class).values());

        List<ScriptValidator> validators = new ArrayList<>();
        validators.add(new DefaultScriptValidator());
        validators.addAll(context.getBeansOfType(ScriptValidator.class).values());

        List<AuditStore> auditStores = new ArrayList<>(
                context.getBeansOfType(AuditStore.class).values());

        return new HarkenService(
                new GroovyScriptEngine(classLoaderOf(context), 256),
                resolver,
                contributors,
                validators,
                auditStores,
                SpringTransactionBridge.detect(context),
                options != null ? options : HarkenOptions.defaults(),
                writeGuard,
                catalog == null ? NoopScriptCatalog.INSTANCE : catalog);
    }

    /**
     * 创建类名索引，用于 {@code import} 补全。
     *
     * <p>构建只枚举 {@code .class} 的路径、不加载类，百级类在毫秒以内，
     * 可以直接在启动时建好。方法表不在这里 —— 那是按需的，见
     * {@link com.harlanking.harken.core.metadata.MethodCatalog}。
     *
     * @param context  Spring 容器
     * @param packages 业务包根，例如 {@code com.demo}
     * @return 索引，永不返回 {@code null}
     */
    public static ClassIndex classIndex(ApplicationContext context, Collection<String> packages) {
        return ClasspathClassIndex.load(context, packages);
    }

    private static ClassLoader classLoaderOf(ApplicationContext context) {
        ClassLoader cl = context.getClassLoader();
        return cl != null ? cl : Thread.currentThread().getContextClassLoader();
    }
}
