package com.harlanking.harken.spring.boot;

import com.harlanking.harken.core.ConfigFilePermissions;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 找出「哪几个磁盘文件承载了 Harken 的配置」，并检查它们的权限。
 *
 * <h2>怎么找</h2>
 *
 * <p>不靠猜路径，而是问 Spring：每个配置项都带着 <b>origin</b>（它来自哪个文件的第几行）。
 * 遍历属性源，把所有 {@code harken.*} 键的来源文件收起来 ——
 * {@code application.yml}、{@code application-prod.yml}、{@code --spring.config.additional-location}
 * 指向的外部文件都因此覆盖到，不需要使用者额外告诉 Harken「我的配置在哪」。
 *
 * <h2>找不到怎么办</h2>
 *
 * <p>配置打在 jar 里（{@code ClassPathResource}）、来自环境变量、来自命令行参数时，
 * 都<b>没有</b>可检查的文件 —— 这些情况合法且常见，静静跳过即可。
 * 不猜、不编一个路径出来检查，是本类的基本纪律。
 *
 * @author harlan
 * @since 1.0.0
 */
final class HarkenConfigFileCheck {

    /** 只看 Harken 自己的配置项，避免把整个应用的配置来源都算进来。 */
    private static final String PREFIX = "harken.";

    private HarkenConfigFileCheck() {
    }

    /**
     * 检查所有承载 Harken 配置的磁盘文件。
     *
     * @param environment Spring 环境，可为 {@code null}
     * @return 每个文件的检查结果；没有可检查的文件时返回空列表
     */
    static List<ConfigFilePermissions.Result> check(Environment environment) {
        List<ConfigFilePermissions.Result> results = new ArrayList<>();
        for (Path file : files(environment)) {
            results.add(ConfigFilePermissions.check(file));
        }
        return results;
    }

    /**
     * 从属性源的 origin 里收集配置文件路径。
     *
     * @param environment Spring 环境，可为 {@code null}
     * @return 去重后的绝对路径，顺序即属性源顺序
     */
    static List<Path> files(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return List.of();
        }
        Set<Path> paths = new LinkedHashSet<>();
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)
                    || !(source instanceof OriginLookup<?> lookup)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (name == null || !name.startsWith(PREFIX)) {
                    continue;
                }
                Path path = toPath(originOf(lookup, name));
                if (path != null) {
                    paths.add(path);
                }
            }
        }
        return List.copyOf(paths);
    }

    @SuppressWarnings("unchecked")
    private static Origin originOf(OriginLookup<?> lookup, String name) {
        return ((OriginLookup<String>) lookup).getOrigin(name);
    }

    /**
     * 把 origin 翻译成磁盘路径。只有真正落在文件系统上的才返回。
     *
     * <p><b>不要按具体 Resource 类型判断</b>：Spring Boot 3 给配置文件用的
     * origin resource 是 {@code PathResource}，既不是 {@code FileSystemResource}
     * 也不是 {@code UrlResource} —— 按类型写只会让整条检查静默失效（踩过）。
     * 用 {@link Resource#isFile()} 与 URI 协议判断才是稳的。
     *
     * @param origin 配置项来源，可为 {@code null}
     * @return 绝对路径，或 {@code null}（jar 内、环境变量、命令行）
     */
    private static Path toPath(Origin origin) {
        if (!(origin instanceof TextResourceOrigin textOrigin)) {
            return null;
        }
        Resource resource = textOrigin.getResource();
        if (resource == null) {
            return null;
        }
        try {
            if (resource.isFile()) {
                return resource.getFile().toPath().toAbsolutePath();
            }
        } catch (Exception ignored) {
            // jar 内资源、不可解析的路径都走这里，继续试 URI
        }
        try {
            URI uri = resource.getURI();
            if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                return Paths.get(uri).toAbsolutePath();
            }
        } catch (Exception ignored) {
            // 同上：拿不到就当没有可检查的文件
        }
        return null;
    }
}
