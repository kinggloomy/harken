package com.harlanking.harken.core.catalog;

import groovy.json.JsonSlurper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 JSON 文本解析成 {@link ScriptDefinition} 列表。
 *
 * <p>用 Groovy 自带的 {@link JsonSlurper}（core 已经依赖 Groovy），不引入 Jackson / Gson。
 * 解析是「宽容失败」的：某一条坏数据只会 WARN 并跳过，不让一条坏 JSON 阻断整个目录。
 *
 * <p>顶层既可以是单个对象（一条脚本），也可以是数组（多条）。数组里每个元素都是一条。
 *
 * @author harlan
 * @since 1.0.0
 */
final class CatalogParser {

    private static final Logger log = LoggerFactory.getLogger(CatalogParser.class);
    private static final JsonSlurper SLURPER = new JsonSlurper();

    private CatalogParser() {
    }

    /**
     * 解析一段 JSON。
     *
     * @param sourceName 来源名（用于日志，例如文件路径或 jar 内资源名）
     * @param text       JSON 字符串
     * @return 解析出的脚本定义；解析失败返回空列表（已打 WARN）
     */
    static List<ScriptDefinition> parse(String sourceName, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // Groovy 4 移除了 JsonSlurper.parse(String)，用 char[] 重载（也避免额外 Reader 对象）
        return fromRoot(sourceName, SLURPER.parse(text.toCharArray()));
    }

    /**
     * 解析输入流（强制 UTF-8）。
     *
     * @param sourceName 来源名
     * @param in         输入流，方法内不关闭，由调用方负责
     * @return 解析出的脚本定义
     */
    static List<ScriptDefinition> parse(String sourceName, InputStream in) {
        return fromRoot(sourceName, SLURPER.parse(in, java.nio.charset.StandardCharsets.UTF_8.name()));
    }

    /**
     * 解析字符流。
     *
     * @param sourceName 来源名
     * @param reader     字符流，方法内不关闭
     * @return 解析出的脚本定义
     */
    static List<ScriptDefinition> parse(String sourceName, Reader reader) {
        return fromRoot(sourceName, SLURPER.parse(reader));
    }

    @SuppressWarnings("unchecked")
    private static List<ScriptDefinition> fromRoot(String sourceName, Object root) {
        if (root instanceof Map) {
            ScriptDefinition d = toDefinition(sourceName, (Map<String, Object>) root);
            return d == null ? List.of() : List.of(d);
        }
        if (root instanceof List) {
            List<ScriptDefinition> out = new ArrayList<>();
            for (Object item : (List<Object>) root) {
                if (item instanceof Map) {
                    ScriptDefinition d = toDefinition(sourceName, (Map<String, Object>) item);
                    if (d != null) {
                        out.add(d);
                    }
                } else {
                    log.warn("Harken 目录 {} 的数组元素不是对象，已跳过", sourceName);
                }
            }
            return List.copyOf(out);
        }
        log.warn("Harken 目录 {} 根节点既不是对象也不是数组，已跳过", sourceName);
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static ScriptDefinition toDefinition(String sourceName, Map<String, Object> m) {
        try {
            String id = str(m.get("id"));
            if (id == null) {
                log.warn("Harken 目录 {} 有一条缺 id，已跳过", sourceName);
                return null;
            }
            String script = str(m.get("script"));
            if (script == null) {
                log.warn("Harken 目录 {} 的脚本 {} 缺 script，已跳过", sourceName, id);
                return null;
            }
            String name = str(m.get("name"));
            if (name == null) {
                name = id;
            }
            String description = str(m.get("description"));
            RiskLevel risk = RiskLevel.parse(str(m.get("risk")));
            boolean commitAllowed = bool(m.get("commitAllowed"), false);
            List<ParamDef> params = parseParams(sourceName, id, m.get("params"));
            return new ScriptDefinition(id, name, description, script, risk, commitAllowed, params);
        } catch (RuntimeException e) {
            log.warn("Harken 目录 {} 解析失败，已跳过：{}", sourceName, e.toString());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ParamDef> parseParams(String sourceName, String id, Object raw) {
        if (!(raw instanceof List)) {
            return List.of();
        }
        List<ParamDef> out = new ArrayList<>();
        for (Object item : (List<Object>) raw) {
            if (!(item instanceof Map)) {
                log.warn("Harken 目录 {} 脚本 {} 的 params 元素不是对象，跳过", sourceName, id);
                continue;
            }
            Map<String, Object> pm = (Map<String, Object>) item;
            try {
                String name = str(pm.get("name"));
                if (name == null) {
                    log.warn("Harken 目录 {} 脚本 {} 的 param 缺 name，跳过", sourceName, id);
                    continue;
                }
                String type = str(pm.get("type"));
                boolean required = bool(pm.get("required"), false);
                String label = str(pm.get("label"));
                String defaultValue = str(pm.get("defaultValue"));
                String description = str(pm.get("description"));
                out.add(new ParamDef(name, type, required, label, defaultValue, description));
            } catch (RuntimeException e) {
                log.warn("Harken 目录 {} 脚本 {} 的 param 解析失败，跳过：{}", sourceName, id, e.toString());
            }
        }
        return List.copyOf(out);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static boolean bool(Object v, boolean fallback) {
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(v).trim());
    }
}
