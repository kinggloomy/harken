package com.harlanking.harken.core;

import com.harlanking.harken.core.engine.GroovyScriptEngine;
import com.harlanking.harken.core.engine.ScriptEngine;
import com.harlanking.harken.core.impl.DefaultScriptValidator;
import com.harlanking.harken.core.impl.MapBeanResolver;
import com.harlanking.harken.core.spi.AuditStore;
import com.harlanking.harken.core.spi.BeanResolver;
import com.harlanking.harken.core.spi.BindingContributor;
import com.harlanking.harken.core.spi.ScriptValidator;
import com.harlanking.harken.core.spi.TransactionBridge;
import com.harlanking.harken.core.spi.WriteGuard;
import com.harlanking.harken.core.catalog.NoopScriptCatalog;
import com.harlanking.harken.core.catalog.ScriptCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 非 Spring 场景的编程式装配入口。
 *
 * <p>Spring 用户不会看到这个类 —— 自动配置会帮他装配。这里是给「纯 Java main」、
 * Vert.x、Quarkus、单元测试用的：
 *
 * <pre>{@code
 * HarkenService harken = HarkenRuntime.builder()
 *         .bean("orderService", orderService)
 *         .transactionBridge(myTxBridge)
 *         .build();
 *
 * ScriptResult r = harken.execute(
 *         new ReadWriteRequest("return orderService.count()", false), "harlan", "127.0.0.1");
 * }</pre>
 *
 * <p>装配规则：{@code DefaultScriptValidator} 永远会被加上，即使调用方没有显式配置
 * ——禁止编译期执行特性这条规则不允许被遗漏。
 *
 * @author harlan
 * @since 1.0.0
 * @see HarkenService
 */
public final class HarkenRuntime {

    private HarkenRuntime() {
    }

    /**
     * 创建装配器。
     *
     * @return 新的构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 装配器。所有方法都返回 {@code this}，可以链式调用。
     *
     * <p>没有调用 {@link #beanResolver(BeanResolver)} 时，{@link #bean(String, Object)}
     * 注册的对象会被包成 {@link MapBeanResolver}；两者都没设则用
     * {@link BeanResolver#empty()}。
     */
    public static final class Builder {

        private final Map<String, Object> staticBeans = new LinkedHashMap<>();
        private final List<BindingContributor> contributors = new ArrayList<>();
        private final List<ScriptValidator> validators = new ArrayList<>();
        private final List<AuditStore> auditStores = new ArrayList<>();
        private BeanResolver beanResolver;
        private TransactionBridge transactionBridge = TransactionBridge.NoopTransactionBridge.INSTANCE;
        private WriteGuard writeGuard = WriteGuard.NONE;
        private HarkenOptions options = HarkenOptions.defaults();
        private ScriptEngine engine;
        private ClassLoader classLoader;
        private int cacheCapacity = 256;
        private ScriptCatalog catalog = NoopScriptCatalog.INSTANCE;

        private Builder() {
        }

        /**
         * 直接放一个对象进上下文，脚本里按名字使用。
         *
         * @param name     脚本中的变量名，例如 {@code orderService}
         * @param instance 实例，不能为 {@code null}
         * @return this
         */
        public Builder bean(String name, Object instance) {
            this.staticBeans.put(name, instance);
            return this;
        }

        /**
         * 指定 Bean 解析器，优先于 {@link #bean(String, Object)} 注册的静态对象。
         *
         * @param resolver 解析器
         * @return this
         */
        public Builder beanResolver(BeanResolver resolver) {
            this.beanResolver = resolver;
            return this;
        }

        /**
         * 添加变量注入器。
         *
         * @param contributor 注入器，实现必须线程安全
         * @return this
         */
        public Builder contributor(BindingContributor contributor) {
            this.contributors.add(contributor);
            return this;
        }

        /**
         * 添加校验器。内置的 {@link DefaultScriptValidator} 始终生效，无法通过这里移除。
         *
         * @param validator 校验器
         * @return this
         */
        public Builder validator(ScriptValidator validator) {
            this.validators.add(validator);
            return this;
        }

        /**
         * 添加审计存储，可多次调用。一个都不加时默认使用
         * {@link com.harlanking.harken.core.impl.Slf4jAuditStore}。
         *
         * @param store 审计实现
         * @return this
         */
        public Builder auditStore(AuditStore store) {
            this.auditStores.add(store);
            return this;
        }

        /**
         * 指定事务桥。不指定时为 {@link TransactionBridge.NoopTransactionBridge}，
         * 此时写操作会被拒绝（SR-02）。
         *
         * @param bridge 事务实现
         * @return this
         */
        public Builder transactionBridge(TransactionBridge bridge) {
            this.transactionBridge = bridge;
            return this;
        }

        /**
         * 指定写入守卫（影响行数上限、关键表黑名单）。
         *
         * <p>core 本身不提供实现 —— 需要 JDBC 层计数时引 {@code harken-jdbc}，
         * 用 {@code JdbcWriteGuard} 包一层 {@code DataSource}。
         *
         * @param guard 守卫实现，{@code null} 会被忽略
         * @return this
         */
        public Builder writeGuard(WriteGuard guard) {
            this.writeGuard = guard;
            return this;
        }

        /**
         * 指定执行参数。
         *
         * @param options 参数对象，{@code null} 会被忽略
         * @return this
         */
        public Builder options(HarkenOptions options) {
            this.options = options;
            return this;
        }

        /**
         * 替换脚本引擎（例如换成 Kotlin Script 实现）。不指定时使用
         * {@link GroovyScriptEngine}。
         *
         * @param engine 引擎实现
         * @return this
         */
        public Builder engine(ScriptEngine engine) {
            this.engine = engine;
            return this;
        }

        /**
         * 指定编译器的父 ClassLoader。脚本要 import 业务类，必须能看见业务类。
         *
         * <p>不指定时用线程上下文 ClassLoader —— 在 Spring Boot fat jar、Tomcat、
         * 单元测试里这个值通常都是对的。
         *
         * @param classLoader 父加载器
         * @return this
         */
        public Builder classLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
            return this;
        }

        /**
         * 编译缓存容量（LRU）。默认 256，最小 16。
         *
         * <p>缓存的意义不只是提速：{@code parseClass} 的产物永不卸载，
         * 不做缓存会把 Metaspace 跑满。
         *
         * @param capacity 最多缓存多少个不同脚本
         * @return this
         */
        public Builder compileCacheCapacity(int capacity) {
            this.cacheCapacity = capacity;
            return this;
        }

        /**
         * 指定预置脚本目录（受限模式的数据来源）。
         *
         * <p>Library 形态下这样用：
         * <pre>{@code
         * HarkenRuntime.builder()
         *         .catalog(new FileScriptCatalog(Path.of("scripts.json")))
         *         .build();
         * }</pre>
         * 不指定时为空目录（{@link NoopScriptCatalog}），UI 不会进入受限模式。
         *
         * @param catalog 目录实现；{@code null} 会被忽略（等同空目录）
         * @return this
         */
        public Builder catalog(ScriptCatalog catalog) {
            this.catalog = catalog == null ? NoopScriptCatalog.INSTANCE : catalog;
            return this;
        }

        /**
         * 装配并返回编排器。
         *
         * @return 可直接使用的 {@link HarkenService}
         */
        public HarkenService build() {
            BeanResolver resolver = beanResolver != null
                    ? beanResolver
                    : (staticBeans.isEmpty() ? BeanResolver.empty() : new MapBeanResolver(staticBeans));

            ScriptEngine scriptEngine = engine != null
                    ? engine
                    : new GroovyScriptEngine(
                            classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader(),
                            cacheCapacity);

            List<ScriptValidator> allValidators = new ArrayList<>();
            allValidators.add(new DefaultScriptValidator());
            allValidators.addAll(validators);

            return new HarkenService(scriptEngine, resolver, contributors, allValidators,
                    auditStores, transactionBridge, options, writeGuard, catalog);
        }
    }
}
