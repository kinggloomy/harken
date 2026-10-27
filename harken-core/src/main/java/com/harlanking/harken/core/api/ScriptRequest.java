package com.harlanking.harken.core.api;

import java.util.Map;

/**
 * 脚本执行请求的顶层类型。
 *
 * <p>用 sealed + 两个互斥 record 表达「AI 通道」与「人类通道」，而不是用一个 boolean flag。
 * 这样 {@link com.harlanking.harken.core.HarkenService} 可以只为人类通道的签名暴露
 * {@code commit}：AI 通道的请求类型里根本不存在这个字段，
 * <b>「AI 写库」在编译期就不成立</b>。
 *
 * <p>这是 ADR-22 的落地方式。不要改成
 * {@code execute(String script, boolean commit)}，那等于把只读降级成运行时判断。
 *
 * @author harlan
 * @since 1.0.0
 */
public sealed interface ScriptRequest permits ReadOnlyRequest, ReadWriteRequest {

    /**
     * 脚本源码。
     *
     * @return 非 {@code null} 的 Groovy 源码字符串
     */
    String script();

    /**
     * 该通道是否允许提交事务。
     *
     * <p>返回 {@code true} 只代表「类型上允许写」，真正能否提交还要过三道闸：
     * 事务管理器是否可用（SR-02）、{@code ScriptValidator} 是否放行、影响行数是否超阈值。
     *
     * @return AI 通道恒为 {@code false}；人类通道恒为 {@code true}
     */
    boolean writable();

    /**
     * 审计用的通道标识。
     *
     * @return {@code ai} 或 {@code human}
     */
    String channel();

    /**
     * 执行时注入脚本的命名参数（受限模式下由 UI 填表产生）。
     *
     * <p>返回非空时，这些值会作为名为 {@code params} 的 Map 注入脚本作用域，
     * 脚本里用 {@code params.orderCode} 访问。自由编写模式（FULL）恒为空 Map，
     * 不会污染用户脚本的命名空间。
     *
     * @return 参数 Map；默认可为空
     */
    default Map<String, Object> params() {
        return Map.of();
    }
}
