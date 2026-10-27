package com.harlanking.harken.core.catalog;

import java.util.List;
import java.util.Optional;

/**
 * 预置脚本目录。把「脚本从哪来」与「脚本怎么执行」解耦：执行引擎只认识这个接口，
 * 不关心脚本是写在 jar 里、外部文件、还是数据库里。
 *
 * <p>内置实现：
 * <ul>
 *   <li>{@link ClasspathScriptCatalog} —— jar 内 {@code harken-scripts/*.json}</li>
 *   <li>{@link FileScriptCatalog} —— 外部文件/目录，支持热加载</li>
 *   <li>{@link NoopScriptCatalog} —— 空目录</li>
 * </ul>
 *
 * <p>数据库、Nacos、Apollo 等来源：实现本接口并向容器注册为 Bean（Spring），
 * 或传给 {@code HarkenRuntime.builder().catalog(...)}（Library 形态）即可。
 *
 * @author harlan
 * @since 1.0.0
 */
public interface ScriptCatalog {

    /**
     * 列出所有脚本。
     *
     * @return 不可变列表，永不为 {@code null}
     */
    List<ScriptDefinition> list();

    /**
     * 按 id 找一条。
     *
     * @param id 脚本 id
     * @return 命中返回，否则空
     */
    default Optional<ScriptDefinition> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return list().stream()
                .filter(d -> d.id().equals(id))
                .findFirst();
    }
}
