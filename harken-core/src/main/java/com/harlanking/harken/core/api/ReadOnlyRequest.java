package com.harlanking.harken.core.api;

/**
 * AI 通道的脚本请求：<b>只读</b>，且类型上没有任何写入口。
 *
 * <p>没有 {@code commit} 字段，不是「默认 false」，而是「不存在」。
 * 反序列化时应开启 {@code FAIL_ON_UNKNOWN_PROPERTIES}，AI 若在请求体里塞
 * {@code commit} 会直接得到 400，而不是被静默忽略 —— 静默忽略会让 AI 误以为写成功了。
 *
 * @param script 要执行的 Groovy 源码。必须 {@code return} 结果，不要依赖 {@code println}
 * @author harlan
 * @since 1.0.0
 */
public record ReadOnlyRequest(String script) implements ScriptRequest {

    /**
     * @throws IllegalArgumentException 当 {@code script} 为 {@code null}
     */
    public ReadOnlyRequest {
        if (script == null) {
            throw new IllegalArgumentException("script must not be null");
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean writable() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public String channel() {
        return "ai";
    }
}
