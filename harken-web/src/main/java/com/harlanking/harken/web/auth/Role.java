package com.harlanking.harken.web.auth;

/**
 * 操作角色。只有三个，刻意不多。
 *
 * <p>每加一个角色，「谁能不能干这件事」的组合就要重新推一遍，而推错的方向
 * 永远是「多给了权限」。三个角色覆盖实际需求：
 *
 * <table border="1">
 *   <caption>角色权限</caption>
 *   <tr><th>角色</th><th>执行脚本</th><th>提交事务</th><th>重载凭据</th></tr>
 *   <tr><td>{@link #READONLY}</td><td>✅（只能试跑）</td><td>❌</td><td>❌</td></tr>
 *   <tr><td>{@link #OPERATOR}</td><td>✅</td><td>✅</td><td>❌</td></tr>
 *   <tr><td>{@link #ADMIN}</td><td>✅</td><td>✅</td><td>✅</td></tr>
 * </table>
 *
 * <p>注意 {@link #READONLY} 仍然「能执行脚本」：它可以把脚本跑起来看结果，
 * 只是不能提交。这与 AI 通道的严格只读不同 —— 后者连试跑都不允许写，
 * 靠的是 {@link com.harlanking.harken.core.api.ReadOnlyRequest} 这个类型，
 * 而不是角色。
 *
 * @author harlan
 * @since 1.0.0
 */
public enum Role {

    /** 只能试跑，不能提交。适合只读账号做临时排查。 */
    READONLY,

    /** 可执行并提交。日常运维账号。 */
    OPERATOR,

    /** 可提交，并可重载凭据配置。 */
    ADMIN;

    /**
     * 是否允许把 {@code commit=true} 的请求放行。
     *
     * @return {@code OPERATOR} 与 {@code ADMIN} 返回 {@code true}
     */
    public boolean canCommit() {
        return this != READONLY;
    }

    /**
     * 是否允许重载凭据。
     *
     * @return 仅 {@code ADMIN} 返回 {@code true}
     */
    public boolean canReloadCredentials() {
        return this == ADMIN;
    }

    /**
     * 宽容解析配置里的角色名，大小写不敏感。
     *
     * @param text 配置值，例如 {@code admin}、{@code Operator}
     * @return 对应角色
     * @throws IllegalArgumentException 无法识别时抛出，避免「拼错角色名导致静默降级成某个默认值」
     */
    public static Role parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("角色不能为空");
        }
        for (Role r : values()) {
            if (r.name().equalsIgnoreCase(text.trim())) {
                return r;
            }
        }
        throw new IllegalArgumentException("未知角色 '" + text + "'，可选值：ADMIN / OPERATOR / READONLY");
    }
}
