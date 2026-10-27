package com.harlanking.harken.web.auth;

/**
 * 把配置里的密码文本解析成实际用于校验的秘密值。
 *
 * <h2>支持三种写法</h2>
 * <table border="1">
 *   <caption>密码来源</caption>
 *   <tr><th>配置值</th><th>解释</th><th>适用</th></tr>
 *   <tr><td>{@code env:HARKEN_ADMIN_PASSWORD}</td><td>读同名环境变量</td><td><b>生产推荐</b>：密码不进仓库</td></tr>
 *   <tr><td>{@code pbkdf2-sha256$...}</td><td>PBKDF2 哈希串</td><td>配置文件可入仓库，但需可写权限保护</td></tr>
 *   <tr><td>{@code 随便一串}</td><td>明文</td><td>只适合本地测试，启动会 WARN</td></tr>
 * </table>
 *
 * <h2>环境变量缺失时怎么办</h2>
 * <p><b>直接让该账号失效并报错</b>，而不是退回明文、也不是退回空密码。
 * 唯一安全的降级方向是「更严」，任何「读不到就用默认值」的写法都会变成后门。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class SecretResolver {

    /** 环境变量前缀。 */
    public static final String ENV_PREFIX = "env:";

    private SecretResolver() {
    }

    /**
     * 解析配置值。
     *
     * @param configured 配置里的原始文本
     * @return 实际用于校验的秘密值（哈希串或明文）
     * @throws IllegalStateException 当引用的环境变量不存在或为空
     */
    public static String resolve(String configured) {
        if (configured == null) {
            throw new IllegalStateException("密码配置为 null");
        }
        if (!configured.startsWith(ENV_PREFIX)) {
            return configured;
        }
        String name = configured.substring(ENV_PREFIX.length()).trim();
        if (name.isEmpty()) {
            throw new IllegalStateException("env: 后缺少环境变量名");
        }
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("环境变量 " + name + " 不存在或为空；"
                    + "该账号已被禁用。请设置它，或改用其他密码写法。");
        }
        return value;
    }

    /**
     * 判断配置值是否引用了环境变量。
     *
     * @param configured 配置原文
     * @return 是否 {@code env:} 形式
     */
    public static boolean isEnvReference(String configured) {
        return configured != null && configured.startsWith(ENV_PREFIX);
    }
}
