package com.harlanking.harken.web.auth;

import java.util.Objects;

/**
 * 一条账号配置。密码字段存的是「已解析的最终值」，不是配置里的原始文本。
 *
 * <p>为什么要在装配阶段就把 {@code env:HARKEN_ADMIN_PASSWORD} 解析掉，而不是每次登录时读环境变量：
 * <ul>
 *   <li>环境变量在进程生命周期内不会变，每次读是浪费；</li>
 *   <li>更重要的是「配置解析失败要在启动时暴露」。等到第一次登录才发现环境变量名写错，
 *       那时候人已经在运维现场等着进控制台了。</li>
 * </ul>
 *
 * @param username 用户名，大小写敏感
 * @param secret   密码：可能是 PBKDF2 哈希串，也可能是明文
 * @param role     角色
 * @author harlan
 * @since 1.0.0
 */
public record Credential(String username, String secret, Role role) {

    /**
     * @throws IllegalArgumentException 用户名为空或角色为 {@code null}
     */
    public Credential {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        Objects.requireNonNull(role, "role");
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("用户 " + username + " 的密码不能为空");
        }
    }

    /**
     * 便于日志输出：绝不打印密码内容。
     *
     * @return 例如 {@code admin(ADMIN, pbkdf2)}
     */
    public String describe() {
        return username + "(" + role + ", " + (PasswordHash.isHashed(secret) ? "pbkdf2" : "明文") + ")";
    }
}
