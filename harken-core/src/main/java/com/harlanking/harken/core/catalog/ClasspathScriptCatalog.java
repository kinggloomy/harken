package com.harlanking.harken.core.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 从 classpath 的 {@code harken-scripts/*.json} 加载脚本。
 *
 * <p>同时支持两种运行形态：
 * <ul>
 *   <li>开发期 —— classes 在文件系统上（{@code target/classes}），URL 协议是 {@code file}，
 *       直接列目录；</li>
 *   <li>打包后 —— classes 在 jar 里，URL 协议是 {@code jar}，
 *       无法用 {@code new File(url)} 列目录，要走 jar 条目枚举。</li>
 * </ul>
 *
 * <p>classpath 资源在运行期不变（要改得重新打包），所以这个实现<b>不</b>热加载，
 * 第一次 {@link #list()} 就全部解析好。需要热加载请用 {@link FileScriptCatalog}。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class ClasspathScriptCatalog implements ScriptCatalog {

    private static final Logger log = LoggerFactory.getLogger(ClasspathScriptCatalog.class);

    /** 默认目录。 */
    public static final String DEFAULT_BASE = "harken-scripts";

    private final ClassLoader classLoader;
    private final String base;

    /** 用上下文 ClassLoader 与默认目录。 */
    public ClasspathScriptCatalog() {
        this(Thread.currentThread().getContextClassLoader(), DEFAULT_BASE);
    }

    /**
     * 指定 ClassLoader 与目录。
     *
     * @param classLoader 用来找资源的加载器；{@code null} 用上下文加载器
     * @param base        资源目录，例如 {@code harken-scripts}
     */
    public ClasspathScriptCatalog(ClassLoader classLoader, String base) {
        this.classLoader = classLoader == null
                ? Thread.currentThread().getContextClassLoader() : classLoader;
        this.base = base == null || base.isBlank() ? DEFAULT_BASE : base.trim();
    }

    @Override
    public List<ScriptDefinition> list() {
        List<ScriptDefinition> all = new ArrayList<>();
        try {
            Enumeration<URL> roots = classLoader.getResources(base);
            while (roots.hasMoreElements()) {
                collect(roots.nextElement(), all);
            }
        } catch (IOException e) {
            log.warn("Harken 加载 classpath 目录 {} 失败：{}", base, e.toString());
        }
        return List.copyOf(all);
    }

    private void collect(URL url, List<ScriptDefinition> out) {
        String protocol = url.getProtocol();
        if ("file".equals(protocol)) {
            collectFromDirectory(url, out);
        } else if ("jar".equals(protocol) || "wsjar".equals(protocol) || "vfs".equals(protocol)) {
            collectFromJar(url, out);
        } else {
            // 兜底：当成单个 JSON 文件读
            readOne(url, out);
        }
    }

    private void collectFromDirectory(URL url, List<ScriptDefinition> out) {
        try {
            Path dir = Path.of(url.toURI());
            if (!Files.isDirectory(dir)) {
                readOne(url, out);
                return;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(p -> readPath(p, out));
            }
        } catch (Exception e) {
            log.warn("Harken 列举目录 {} 失败：{}", url, e.toString());
        }
    }

    private void collectFromJar(URL url, List<ScriptDefinition> out) {
        try {
            URLConnection conn = url.openConnection();
            if (!(conn instanceof JarURLConnection jarConn)) {
                return;
            }
            String entryBase = jarConn.getEntryName();
            if (entryBase == null) {
                return;
            }
            if (!entryBase.endsWith("/")) {
                entryBase = entryBase + "/";
            }
            try (JarFile jar = jarConn.getJarFile()) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    String name = e.getName();
                    if (e.isDirectory() || !name.startsWith(entryBase) || !name.endsWith(".json")) {
                        continue;
                    }
                    // 只取 base 下一层，不递归进子目录
                    String relative = name.substring(entryBase.length());
                    if (relative.isEmpty() || relative.contains("/")) {
                        continue;
                    }
                    try (InputStream in = jar.getInputStream(e)) {
                        out.addAll(CatalogParser.parse(name, in));
                    } catch (IOException ex) {
                        log.warn("Harken 读取 jar 内 {} 失败：{}", name, ex.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Harken 列举 jar 内 {} 失败：{}", url, e.toString());
        }
    }

    private void readPath(Path p, List<ScriptDefinition> out) {
        try (InputStream in = Files.newInputStream(p)) {
            out.addAll(CatalogParser.parse(p.toString(), in));
        } catch (IOException e) {
            log.warn("Harken 读取 {} 失败：{}", p, e.toString());
        }
    }

    private void readOne(URL url, List<ScriptDefinition> out) {
        try (InputStream in = url.openStream()) {
            out.addAll(CatalogParser.parse(url.toString(), in));
        } catch (IOException e) {
            log.warn("Harken 读取 {} 失败：{}", url, e.toString());
        }
    }
}
