package com.harlanking.harken.core.metadata;

import com.harlanking.harken.core.spi.BeanResolver;

import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 方法表：Bean 的实例方法与任意类的静态方法，都按需读、按类缓存。
 *
 * <h2>为什么不全量扫一遍</h2>
 * <p>全量扫描要么把几百个类塞进 Metaspace（实测 801 类 / +6.9 MB，且不可卸载），
 * 要么在启动时白付几百毫秒。而用户一次只会在一个名字后面敲点号 ——
 * 读一个类的 {@code getMethods()} 只要 0.62 μs，全量预扫是纯浪费。
 *
 * <h2>{@code initialize=false} 不是可选项</h2>
 * <p>{@link Class#forName(String, boolean, ClassLoader)} 的第二个参数必须传 {@code false}。
 * 传 {@code true} 会执行目标类的静态初始化块，而补全一个方法清单绝不该顺带跑一遍
 * 业务类的 {@code static { ... }}（连库、抢锁、读配置都可能写在里面）。
 *
 * <h2>为什么静态方法接受任意全限定名</h2>
 * <p>不做白名单：能登录 Harken 的人本来就能执行任意 Groovy，也就本来就能加载任意类。
 * 把类型名限制在索引里只会让 {@code java.util.UUID} 这类 JDK 类的静态方法提示不到，
 * 挡不住任何实际风险。只做格式校验 —— 防的是 {@code [Ljava.lang.String;} 这种
 * {@code forName} 认识的数组描述符，以及超长串。
 *
 * <h2>为什么要回答「这个简名实际绑到哪」</h2>
 * <p>脚本里没写 import 的简名，Groovy 会先在默认导入包里找。所以
 * {@code Category.allViews()} 里那个 {@code Category} 绑的是 {@code groovy.lang.Category}，
 * 不是业务包里同名的枚举 —— 补全如果只看索引，就会给出一个运行时根本不存在的方法。
 * {@link #defaultImportOf(String)} 用来把这件事说清楚。
 *
 * <h2>为什么还要「按类型读实例方法」</h2>
 * <p>{@code Category.allViews().forEach(...)} 里第二个点号后面该出现哪些方法，
 * 取决于 {@code allViews()} 的返回类型。前端拿着 {@link MethodMetadata#returnType()}
 * 回过头来问这个类型有哪些实例方法，所以需要一条「按类型名而不是 Bean 名」的入口。
 *
 * <p>本类线程安全。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class MethodCatalog {

    /** 单类型保留的方法上限，防止某个巨型类把响应撑爆。 */
    private static final int MAX_METHODS = 200;

    /** 全限定名长度上限，纯粹为了不让一串垃圾字符进 {@code forName}。 */
    private static final int MAX_TYPE_NAME = 500;

    private static final Pattern TYPE_NAME =
            Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+");

    /** 不带点号的类简名，用于默认导入探测。 */
    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z_$][\\w$]*");

    /**
     * Groovy 脚本的默认导入包，顺序即查找顺序。
     *
     * <p>这六个包是脚本里「没有 import 的简名」的解析来源。补全必须按这个顺序判断，
     * 否则会给出脚本根本调不到的成员。
     */
    private static final List<String> DEFAULT_IMPORTS = List.of(
            "java.lang", "java.util", "java.io", "java.net", "groovy.lang", "groovy.util");

    private final BeanResolver beans;
    private final ClassLoader loader;

    /** 全限定名 → 静态方法表。读不到也存空清单，避免每次点号都重试。 */
    private final ConcurrentMap<String, List<MethodMetadata>> staticCache = new ConcurrentHashMap<>();

    /** 全限定名 → 实例方法表。与静态表分开存：同一个类两张表内容不同。 */
    private final ConcurrentMap<String, List<MethodMetadata>> instanceCache = new ConcurrentHashMap<>();

    /** 全限定名 → 枚举常量名。非枚举存空清单。 */
    private final ConcurrentMap<String, List<String>> constantCache = new ConcurrentHashMap<>();

    /** 全限定名 → 类型参数名（如 {@code List} 的 {@code E}）。 */
    private final ConcurrentMap<String, List<String>> typeVarCache = new ConcurrentHashMap<>();

    /** 简名 → 默认导入命中的类全名。空串表示「没有」，同样要缓存。 */
    private final ConcurrentMap<String, String> defaultCache = new ConcurrentHashMap<>();

    /**
     * @param beans Bean 来源，不能为 {@code null}
     */
    public MethodCatalog(BeanResolver beans) {
        this.beans = beans;
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        this.loader = contextLoader != null ? contextLoader : MethodCatalog.class.getClassLoader();
    }

    /**
     * 脚本里可直接使用的 Bean 名与类型。
     *
     * @return Bean 名到类型全名的映射，按名字排序，永不为 {@code null}
     */
    public Map<String, String> beanTypes() {
        List<String> names = new ArrayList<>(beans.names());
        Collections.sort(names);
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : names) {
            Class<?> type = beans.typeOf(name);
            if (type != null) {
                result.put(name, type.getName());
            }
        }
        return result;
    }

    /**
     * 读一个 Bean 的实例方法。
     *
     * @param beanName Bean 名
     * @return 方法清单；名字不存在或没有可调用方法时为空清单，永不返回 {@code null}
     */
    public List<MethodMetadata> instanceMethods(String beanName) {
        Class<?> type = beanName == null ? null : beans.typeOf(beanName);
        return type == null ? List.of() : MethodSignatures.of(type, false, MAX_METHODS);
    }

    /**
     * 读一个类型的 {@code public static} 方法，结果按类型缓存。
     *
     * @param typeName 全限定类名，例如 {@code com.demo.util.CalcUtil}
     * @return 方法清单；名字不合法、类不存在或没有静态方法时为空清单，永不返回 {@code null}
     */
    public List<MethodMetadata> staticMethods(String typeName) {
        return isTypeName(typeName)
                ? staticCache.computeIfAbsent(typeName, name -> readMethods(name, true))
                : List.of();
    }

    /**
     * 读一个类型的实例方法。链式补全靠它：{@code allViews()} 返回 {@code List<X>}，
     * 第二个点号后面要列的就是 {@code List} 的实例方法。
     *
     * @param typeName 全限定类名，例如 {@code java.util.List}
     * @return 方法清单；名字不合法或类不存在时为空清单，永不返回 {@code null}
     */
    public List<MethodMetadata> instanceMethodsOf(String typeName) {
        return isTypeName(typeName)
                ? instanceCache.computeIfAbsent(typeName, name -> readMethods(name, false))
                : List.of();
    }

    /**
     * 枚举类的常量名。
     *
     * <p>枚举常量是 {@code public static final} 字段，不在方法表里，但它是
     * {@code valueOf} 唯一接受的写法 —— 不列出来，用户就会写出
     * {@code Hexagram.valueOf('兑')} 这种「以为是按中文名查」的脚本，运行时才炸。
     *
     * @param typeName 全限定类名
     * @return 常量名清单，按声明顺序；非枚举或类不存在时为空清单
     */
    public List<String> constantsOf(String typeName) {
        return isTypeName(typeName) ? constantCache.computeIfAbsent(typeName, this::readConstants)
                : List.of();
    }

    /**
     * 类的类型参数名，例如 {@code java.util.List} 的 {@code ["E"]}。
     *
     * <p>前端拿它把方法返回类型里的类型变量换成真实类型：{@code List<Foo>.get(int)}
     * 的返回类型声明是 {@code E}，不换就只知道「是 E」，没法接着补 {@code Foo} 的成员。
     *
     * @param typeName 全限定类名
     * @return 类型参数名清单；无类型参数或类不存在时为空清单
     */
    public List<String> typeVarsOf(String typeName) {
        return isTypeName(typeName) ? typeVarCache.computeIfAbsent(typeName, this::readTypeVars)
                : List.of();
    }

    private static boolean isTypeName(String typeName) {
        return typeName != null && typeName.length() <= MAX_TYPE_NAME
                && TYPE_NAME.matcher(typeName).matches();
    }

    /**
     * 捕获 {@link LinkageError} 而不只是 {@link ClassNotFoundException}：
     * 类文件版本高于当前 JVM（{@code UnsupportedClassVersionError}）、
     * 依赖缺失（{@code NoClassDefFoundError}）都会从这里冒出来，
     * 而这三种情况对补全的结论一样 —— 这个类提示不了，别把整个端点拖垮。
     */
    private List<MethodMetadata> readMethods(String typeName, boolean wantStatic) {
        Class<?> type = load(typeName);
        return type == null ? List.of() : MethodSignatures.of(type, wantStatic, MAX_METHODS);
    }

    private List<String> readConstants(String typeName) {
        Class<?> type = load(typeName);
        if (type == null || !type.isEnum()) {
            return List.of();
        }
        Object[] constants = type.getEnumConstants();
        List<String> names = new ArrayList<>(constants.length);
        for (Object constant : constants) {
            names.add(((Enum<?>) constant).name());
        }
        return List.copyOf(names);
    }

    private List<String> readTypeVars(String typeName) {
        Class<?> type = load(typeName);
        if (type == null) {
            return List.of();
        }
        TypeVariable<?>[] vars = type.getTypeParameters();
        List<String> names = new ArrayList<>(vars.length);
        for (TypeVariable<?> var : vars) {
            names.add(var.getName());
        }
        return List.copyOf(names);
    }

    private Class<?> load(String typeName) {
        try {
            return Class.forName(typeName, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /**
     * 一个<b>没写 import</b> 的简名，在脚本里会被 Groovy 绑到哪个类。
     *
     * <p>补全要知道这个，才能区分两件外观相同、后果完全不同的事：
     * 「这个名字就是索引里那个类」和「这个名字已经被默认导入抢走了」。
     *
     * @param simpleName 类简名，例如 {@code Category}
     * @return 实际绑定的类全名；没有任何同名类时返回 {@code null}
     */
    public String defaultImportOf(String simpleName) {
        if (simpleName == null || simpleName.length() > MAX_TYPE_NAME
                || !SIMPLE_NAME.matcher(simpleName).matches()) {
            return null;
        }
        String hit = defaultCache.computeIfAbsent(simpleName, this::probeDefaultImport);
        return hit.isEmpty() ? null : hit;
    }

    /** 六个包挨个 {@code forName}，命中即返回。失败一次几微秒，结果会进缓存。 */
    private String probeDefaultImport(String simpleName) {
        for (String pkg : DEFAULT_IMPORTS) {
            String candidate = pkg + '.' + simpleName;
            try {
                Class.forName(candidate, false, loader);
                return candidate;
            } catch (ClassNotFoundException | LinkageError e) {
                // 这个包下没有，试下一个
            }
        }
        return "";
    }
}
