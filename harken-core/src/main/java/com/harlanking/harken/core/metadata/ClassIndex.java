package com.harlanking.harken.core.metadata;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 类名索引：按包分组的类简名，供 {@code import} 补全使用。
 *
 * <h2>只存名字，不存 {@link Class}</h2>
 * <p>建索引只读 {@code .class} 文件的路径 —— 文件名就是类名，不用碰字节码，
 * 不用创建 {@link Class}。实测百级类 0.3~0.6 ms，与「反射加载全部类」的几百毫秒
 * 差三个数量级。方法体要靠 {@link MethodCatalog} 按需读。
 *
 * <h2>两个来源，不合并存储</h2>
 * <ul>
 *   <li><b>业务类</b>：由 {@code harken.packages} 决定，构造时一次性建好。</li>
 *   <li><b>JDK 类</b>：在 {@code jrt:/} 文件系统里，{@code classpath*:} 永远找不到
 *       （实测两种 classpath 形态、两套 JDK 都返回 0 类）。单包 10 ms，
 *       全量 14590 类约 128 ms / 1.7 MB，所以按包懒读、读过的缓存。</li>
 * </ul>
 *
 * @author harlan
 * @since 1.0.0
 */
public final class ClassIndex {

    /** 合法包名。{@link #jdkClassesIn(String)} 的参数可能来自 HTTP，拼路径前必须拦。 */
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");

    private static final ClassIndex EMPTY = new ClassIndex(Map.of(), 0);

    /** 包名 → 类简名，两级都已排序，JSON 输出稳定。 */
    private final Map<String, List<String>> byPackage;

    private final int classCount;

    /** JDK 包 → 类简名。读不到也存空清单，避免每次补全都重试一遍。 */
    private final ConcurrentMap<String, List<String>> jdkCache = new ConcurrentHashMap<>();

    private ClassIndex(Map<String, List<String>> byPackage, int classCount) {
        this.byPackage = byPackage;
        this.classCount = classCount;
    }

    /**
     * @return 什么都没有的索引，永不返回 {@code null}
     */
    public static ClassIndex empty() {
        return EMPTY;
    }

    /**
     * 从全限定类名清单建索引。
     *
     * <p>含 {@code $} 的内部类与匿名类被跳过：它们没法写 import，列出来只是噪音。
     *
     * @param typeNames 全限定类名，可为 {@code null}
     * @return 索引，永不返回 {@code null}
     */
    public static ClassIndex of(Collection<String> typeNames) {
        Map<String, Set<String>> grouped = new TreeMap<>();
        if (typeNames != null) {
            for (String typeName : typeNames) {
                if (typeName == null) {
                    continue;
                }
                String name = typeName.trim();
                int dot = name.lastIndexOf('.');
                if (dot <= 0 || dot == name.length() - 1) {
                    continue;
                }
                String simpleName = name.substring(dot + 1);
                if (simpleName.indexOf('$') >= 0) {
                    continue;
                }
                grouped.computeIfAbsent(name.substring(0, dot), key -> new TreeSet<>()).add(simpleName);
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, Set<String>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
            total += entry.getValue().size();
        }
        return new ClassIndex(Collections.unmodifiableMap(result), total);
    }

    /**
     * @return 包名到类简名的映射，不可变，永不为 {@code null}
     */
    public Map<String, List<String>> byPackage() {
        return byPackage;
    }

    /**
     * @return 索引里的类总数（不含 JDK 类）
     */
    public int size() {
        return classCount;
    }

    /**
     * 简名反查全限定名。
     *
     * <p>重名时返回 {@code null} 而不是随便挑一个 —— 挑错的后果是补全给出另一个类的
     * 方法，脚本一跑就报错，比不提示更难查。
     *
     * @param simpleName 类简名，例如 {@code Category}
     * @return 唯一的全限定名；没有或重名时为 {@code null}
     */
    public String fqcnOf(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            return null;
        }
        String found = null;
        for (Map.Entry<String, List<String>> entry : byPackage.entrySet()) {
            if (!entry.getValue().contains(simpleName)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = entry.getKey() + '.' + simpleName;
        }
        return found;
    }

    /**
     * 读一个 JDK 包里的类名，首次调用读 {@code jrt:/}，之后走缓存。
     *
     * @param pkg 包名，例如 {@code java.util}
     * @return 类简名，已排序；包名非法或包不存在时返回空清单，永不返回 {@code null}
     */
    public List<String> jdkClassesIn(String pkg) {
        if (pkg == null || !PACKAGE.matcher(pkg).matches()) {
            return List.of();
        }
        return jdkCache.computeIfAbsent(pkg, ClassIndex::readJdkPackage);
    }

    /**
     * 从 {@code jrt:/} 读一个包的类名。
     *
     * <p>{@code /packages/{pkg}} 下每个条目是一个模块链接，{@code toRealPath()} 之后
     * 才是该模块的根目录 —— 同一个包可能分布在多个模块里（{@code java.util} 就有三个），
     * 所以必须遍历全部链接。
     *
     * <p>捕获 {@link RuntimeException} 是因为 {@code getFileSystem} 在极少数裁剪过的
     * 运行时里会抛 {@code FileSystemNotFoundException}，那不该让补全端点挂掉。
     */
    private static List<String> readJdkPackage(String pkg) {
        Set<String> names = new TreeSet<>();
        try {
            FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path packageRoot = jrt.getPath("/packages/" + pkg);
            if (!Files.isDirectory(packageRoot)) {
                return List.of();
            }
            String relative = pkg.replace('.', '/');
            try (DirectoryStream<Path> modules = Files.newDirectoryStream(packageRoot)) {
                for (Path module : modules) {
                    Path dir = module.toRealPath().resolve(relative);
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.class")) {
                        for (Path file : files) {
                            String name = file.getFileName().toString();
                            int end = name.length() - ".class".length();
                            if (name.indexOf('$') < 0) {
                                names.add(name.substring(0, end));
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return List.copyOf(names);
    }
}
