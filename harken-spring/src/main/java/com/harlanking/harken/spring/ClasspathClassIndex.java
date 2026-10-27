package com.harlanking.harken.spring;

import com.harlanking.harken.core.metadata.ClassIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从 classpath 枚举业务类名，建 {@link ClassIndex}。
 *
 * <h2>只要文件名，不读内容</h2>
 * <p>按包根拼一个 {@code classpath*:} 递归通配去枚举 {@code .class}，在 fat jar
 * （{@code BOOT-INF/classes} 与嵌套 jar）和 exploded 两种形态下都可用，实测百级类 0.3~0.6 ms。
 * 拿到 {@link Resource} 之后把 URL 里包路径之后的部分转成类名 —— 纯字符串操作。
 *
 * <h2>包名只从配置来</h2>
 * <p>pattern 由 {@code harken.packages} 拼出，绝不接受请求参数。这是安全设计的地基：
 * 否则一个 {@code **} 就能让服务端去遍历七千个类（实测 29~50 ms），
 * 多打几个请求就能把 CPU 吃满。
 *
 * <h2>配错了要出声</h2>
 * <p>配了包名却扫到 0 个类是最常见的错误（包名打错、或包不在 classpath 上），
 * 而它的表现只是「补全没反应」，很难自己想到。所以这里两条路径都打 WARN。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class ClasspathClassIndex {

    private static final Logger log = LoggerFactory.getLogger(ClasspathClassIndex.class);

    /** 合法包名。配置项也可能写错，拼 pattern 之前先挡一次。 */
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");

    private static final String CLASS_SUFFIX = ".class";

    private ClasspathClassIndex() {
    }

    /**
     * 按配置的包根建索引。
     *
     * @param context  Spring 容器，用来取类加载器
     * @param packages 业务包根，例如 {@code com.demo}
     * @return 索引；没有可扫描的包时返回空索引，永不返回 {@code null}
     */
    public static ClassIndex load(ApplicationContext context, Collection<String> packages) {
        List<String> roots = normalize(packages);
        if (roots.isEmpty()) {
            log.warn("harken.packages 未配置，import 补全没有候选。"
                    + "配置业务包根（例如 com.demo）后才会有类名提示。");
            return ClassIndex.empty();
        }
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoaderOf(context));
        Set<String> typeNames = new LinkedHashSet<>();
        for (String root : roots) {
            String path = root.replace('.', '/');
            typeNames.addAll(scan(resolver, "classpath*:" + path + "/**/*.class", path));
        }
        if (typeNames.isEmpty()) {
            log.warn("harken.packages={} 下没有扫到任何类，import 补全不会有候选。"
                    + "请确认包名写对了，且这些类在当前 classpath 上。", roots);
        }
        return ClassIndex.of(typeNames);
    }

    private static List<String> scan(ResourcePatternResolver resolver, String pattern, String path) {
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            log.warn("扫描 {} 失败：{}", pattern, e.getMessage());
            return List.of();
        }
        List<String> result = new ArrayList<>(resources.length);
        // URL 形如 file:/D:/x/target/classes/com/demo/A.class 或
        // jar:file:/x/app.jar!/BOOT-INF/classes/com/demo/A.class。
        // 包路径在两种形态下都是分段出现的，从它之后开始截才是类名
        String marker = path + "/";
        for (Resource resource : resources) {
            String url;
            try {
                url = resource.getURL().toString();
            } catch (IOException e) {
                continue;
            }
            int start = url.lastIndexOf(marker);
            if (start < 0 || !url.endsWith(CLASS_SUFFIX)) {
                continue;
            }
            String relative = url.substring(start, url.length() - CLASS_SUFFIX.length());
            result.add(relative.replace('/', '.'));
        }
        return result;
    }

    /** 去空白、去尾点、挡掉非法包名 —— 同时保证 `com.demo` 不会误匹配 `com.demox`。 */
    private static List<String> normalize(Collection<String> packages) {
        if (packages == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>(packages.size());
        for (String raw : packages) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String pkg = raw.trim();
            while (pkg.endsWith(".")) {
                pkg = pkg.substring(0, pkg.length() - 1);
            }
            if (!PACKAGE.matcher(pkg).matches()) {
                log.warn("忽略非法的包名 '{}'（harken.packages 只接受 a.b.c 形式）", raw);
                continue;
            }
            result.add(pkg);
        }
        return result;
    }

    private static ClassLoader classLoaderOf(ApplicationContext context) {
        ClassLoader loader = context.getClassLoader();
        return loader != null ? loader : Thread.currentThread().getContextClassLoader();
    }
}
