package com.harlanking.harken.core.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 从外部文件或目录加载脚本，支持热加载：任何被监视文件的修改时间变了，
 * 下一次 {@link #list()} 会整体重读。
 *
 * <p>热加载的粒度是「整目录」—— 不逐个文件对比，只看整体时间戳。
 * 单文件场景就是该文件本身。这种粗粒度对配置类目录足够，也不会在每次请求上
 * 都做昂贵的文件扫描。
 *
 * <p>解析失败是「局部」的：一条坏 JSON 只跳过那条，目录里其余脚本照常可用。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class FileScriptCatalog implements ScriptCatalog {

    private static final Logger log = LoggerFactory.getLogger(FileScriptCatalog.class);

    private final Path root;
    private volatile List<ScriptDefinition> cache = List.of();
    private volatile long lastSeen = Long.MIN_VALUE;

    /** 单个文件或目录。 */
    public FileScriptCatalog(Path root) {
        this.root = root;
    }

    /** 便捷：从字符串路径构造。 */
    public FileScriptCatalog(String rootPath) {
        this(Path.of(rootPath));
    }

    @Override
    public List<ScriptDefinition> list() {
        if (root == null || !Files.exists(root)) {
            if (lastSeen != Long.MIN_VALUE) {
                log.warn("Harken 外部目录 {} 不存在或已移除，返回空目录", root);
                lastSeen = Long.MIN_VALUE;
                cache = List.of();
            }
            return List.of();
        }
        long stamp = lastModified(root);
        if (stamp == lastSeen) {
            return cache;
        }
        List<ScriptDefinition> loaded = load();
        cache = loaded;
        lastSeen = stamp;
        return loaded;
    }

    private List<ScriptDefinition> load() {
        List<ScriptDefinition> out = new ArrayList<>();
        try {
            if (Files.isDirectory(root)) {
                try (var stream = Files.list(root)) {
                    stream.filter(p -> p.getFileName() != null
                                    && p.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .forEach(p -> readPath(p, out));
                }
            } else {
                readPath(root, out);
            }
        } catch (IOException e) {
            log.warn("Harken 读取外部目录 {} 失败：{}", root, e.toString());
        }
        return List.copyOf(out);
    }

    private void readPath(Path p, List<ScriptDefinition> out) {
        try (InputStream in = Files.newInputStream(p)) {
            out.addAll(CatalogParser.parse(p.toString(), in));
        } catch (IOException e) {
            log.warn("Harken 读取 {} 失败：{}", p, e.toString());
        }
    }

    private long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }
}
