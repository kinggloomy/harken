package com.harlanking.harken.core.catalog;

import java.util.List;

/**
 * 空目录。没有配置目录源时 {@link com.harlanking.harken.core.HarkenService} 默认持有它，
 * 这样 {@code service.catalog().list()} 永远不会是 {@code null}，调用方不必判空。
 *
 * @author harlan
 * @since 1.0.0
 */
public enum NoopScriptCatalog implements ScriptCatalog {

    /** 单例。 */
    INSTANCE;

    @Override
    public List<ScriptDefinition> list() {
        return List.of();
    }
}
