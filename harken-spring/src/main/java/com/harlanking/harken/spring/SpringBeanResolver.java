package com.harlanking.harken.spring;

import com.harlanking.harken.core.spi.BeanResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 Spring 容器里的 Bean 暴露给脚本，按 Bean 名直连。
 *
 * <p>脚本里写：
 * <pre>{@code
 * orderService.queryById(1L)
 * }</pre>
 * 不需要 {@code ctx.getBean("orderService")}。原理是 Groovy 把未声明变量名编译成
 * {@code binding.getVariable("orderService")}，{@code LazyGroovyBinding} 未命中显式变量时
 * 会回调本类（见 {@link com.harlanking.harken.core.spi.BeanResolver}）。
 *
 * <h2>三个实现要点（每一条都是踩过的坑）</h2>
 *
 * <p><b>① 只用 {@code getType()}，绝不用 {@code getBean()} 做扫描。</b>
 * 启动时遍历 {@code getBeanDefinitionNames()} 并逐个 {@code getBean()}，会导致：
 * 所有 {@code @Lazy} 失效、所有 {@code @PostConstruct} 被提前触发（可能连库、起线程）、
 * 循环依赖直接抛异常、启动时间从 3 秒变 30 秒。{@code getType()} 只读 BeanDefinition，
 * 不实例化。
 *
 * <p><b>② 必须脱掉 CGLIB 代理。</b> {@code getType()} 可能返回代理类
 * （{@code OrderService$$SpringCGLIB$$0}），它的方法列表里全是拦截器回调，
 * 补全给用户看毫无意义。用 {@code ClassUtils.getUserClass()} 拿回业务类。
 *
 * <p><b>③ 必须过滤框架内部 Bean。</b> 一个中等规模的 Spring Boot 应用有 500~800 个
 * Bean，其中 80% 是 {@code environment}、{@code beanFactory}、各种
 * {@code XxxAutoConfiguration}。不过滤的话用户想找的 {@code OrderService}
 * 会被彻底淹没。默认按包名前缀排除框架类，也可以通过构造参数显式给白名单。
 *
 * <p>本类不可变、线程安全。构造时完成扫描（只读定义，不实例化），之后所有查询都是
 * 内存操作。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class SpringBeanResolver implements BeanResolver {

    /**
     * 无白名单时被排除的包前缀。这些包里的类对「业务排查」没有价值，
     * 出现在补全列表里只会制造噪音。
     */
    private static final List<String> INFRASTRUCTURE_PREFIXES = List.of(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.",
            "org.springframework.", "org.apache.", "org.eclipse.", "org.jboss.",
            "groovy.", "org.codehaus.groovy.", "org.slf4j.", "ch.qos.logback.",
            "com.fasterxml.", "com.zaxxer.", "io.micrometer.", "reactor."
    );

    private final ApplicationContext context;
    private final Map<String, Class<?>> types;

    /**
     * 扫描全部非框架 Bean。
     *
     * @param context Spring 容器，不能为 {@code null}
     */
    public SpringBeanResolver(ApplicationContext context) {
        this(context, List.of());
    }

    /**
     * 只暴露指定包下的 Bean。
     *
     * <p><b>推荐显式传包名</b>（例如 {@code List.of("com.zm")}）：这是唯一能保证
     * 补全列表干净的办法。传空集合表示「除框架包之外全要」。
     *
     * @param context  Spring 容器，不能为 {@code null}
     * @param packages 包名前缀白名单，例如 {@code com.zm}；空集合表示不过滤业务包
     */
    public SpringBeanResolver(ApplicationContext context, Collection<String> packages) {
        this.context = context;
        this.types = Collections.unmodifiableMap(scan(context, normalize(packages)));
    }

    /**
     * 扫描到的 Bean 数量，用于启动日志与「元数据规模」展示。
     *
     * @return Bean 个数
     */
    public int size() {
        return types.size();
    }

    /** {@inheritDoc} */
    @Override
    public boolean contains(String name) {
        return types.containsKey(name);
    }

    /**
     * {@inheritDoc}
     *
     * <p>这是唯一的实例化入口。Bean 已被销毁、初始化失败、或作用域不合法时返回
     * {@code null}，让脚本得到 {@code MissingPropertyException} 而不是容器内部异常
     * ——后者的堆栈对排查脚本毫无帮助。
     */
    @Override
    public Object get(String name) {
        try {
            return context.getBean(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Class<?> typeOf(String name) {
        return types.get(name);
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> names() {
        return types.keySet();
    }

    // ------------------------------------------------------------------

    private static Map<String, Class<?>> scan(ApplicationContext context, List<String> packages) {
        Map<String, Class<?>> result = new LinkedHashMap<>();
        for (String name : context.getBeanDefinitionNames()) {
            register(result, context, name, packages);
            try {
                for (String alias : context.getAliases(name)) {
                    register(result, context, alias, packages);
                }
            } catch (RuntimeException ignored) {
                // 某些动态注册的 BeanDefinition 没有别名信息，忽略即可
            }
        }
        return result;
    }

    private static void register(Map<String, Class<?>> result, ApplicationContext context,
                                 String name, List<String> packages) {
        if (name == null || name.isEmpty()) {
            return;
        }
        // "&foo" 是 FactoryBean 本身的名字，不是它产出的对象；scopedTarget 是代理内部名
        if (name.startsWith("&") || name.startsWith("scopedTarget.")) {
            return;
        }
        Class<?> type;
        try {
            type = context.getType(name);
        } catch (RuntimeException e) {
            return;
        }
        if (type == null) {
            return;
        }
        Class<?> userType = ClassUtils.getUserClass(type);
        if (!accept(userType, packages)) {
            return;
        }
        result.putIfAbsent(name, userType);
    }

    private static boolean accept(Class<?> type, List<String> packages) {
        String className = type.getName();
        if (!packages.isEmpty()) {
            for (String prefix : packages) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
        for (String prefix : INFRASTRUCTURE_PREFIXES) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> normalize(Collection<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(packages.size());
        for (String pkg : packages) {
            if (pkg == null) {
                continue;
            }
            String trimmed = pkg.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 补尾点，避免 "com.zm" 误匹配 "com.zmx"
            normalized.add(trimmed.endsWith(".") ? trimmed : trimmed + ".");
        }
        return List.copyOf(normalized);
    }
}
