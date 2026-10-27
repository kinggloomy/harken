package com.harlanking.harken.core.api;

import java.util.Map;

/**
 * 人类通道的脚本请求：默认执行后回滚，{@code commit=true} 才允许提交。
 *
 * <p>注意 {@code commit} 只是「意图」，最终能否提交还取决于：事务管理器是否可用（SR-02）、
 * {@code ScriptValidator} 是否放行、影响行数是否超阈值。这些判断在
 * {@link com.harlanking.harken.core.HarkenService} 里做，不在请求对象里做 ——
 * 请求对象只负责表达意图，不负责鉴权。
 *
 * <p><b>{@code commit=false} 不是「数据库只读」。</b> 它的事务语义是
 * {@link com.harlanking.harken.core.spi.TransactionBridge.Mode#ROLLBACK}：
 * 允许脚本执行 UPDATE，看得见影响行数，结束时回滚。
 * 如果把它当成只读事务、把连接置成只读，MySQL / PostgreSQL 会让 UPDATE 直接失败，
 * 「先试跑看看影响几行」这个用法就不存在了。
 *
 * @param script 要执行的 Groovy 源码
 * @param commit {@code true} 表示请求提交事务；{@code false} 表示执行后强制回滚
 * @author harlan
 * @since 1.0.0
 */
public record ReadWriteRequest(String script, boolean commit, Map<String, Object> params) implements ScriptRequest {

    /**
     * 便捷构造：自由编写模式用，无参数注入。
     *
     * @param script Groovy 源码
     * @param commit 是否请求提交
     */
    public ReadWriteRequest(String script, boolean commit) {
        this(script, commit, Map.of());
    }

    /**
     * @throws IllegalArgumentException 当 {@code script} 为 {@code null}
     */
    public ReadWriteRequest {
        if (script == null) {
            throw new IllegalArgumentException("script must not be null");
        }
        if (params == null) {
            params = Map.of();
        }
    }

    /**
     * 便捷构造：试跑。允许写，执行完回滚。
     *
     * <p>纯查询用它也不会多做什么 —— 只是开了个事务然后回滚一个什么都没改的事务。
     *
     * @param script Groovy 源码
     * @return {@code commit=false} 的请求
     */
    public static ReadWriteRequest dryRun(String script) {
        return new ReadWriteRequest(script, false);
    }

    /** {@inheritDoc} */
    @Override
    public boolean writable() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public String channel() {
        return "human";
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> params() {
        return params;
    }
}
