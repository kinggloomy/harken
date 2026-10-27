package com.harlanking.harken.web;

/**
 * 版本号解析。
 *
 * <p>从 jar manifest 的 {@code Implementation-Version} 读（由 Maven 的 jar 插件写入），
 * 读不到就返回 {@code dev}。之所以不写死常量：写死的版本号在发布时一定会忘记同步，
 * 然后你就得到一个「日志说 1.0.0、实际是 1.0.2」的系统。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class Version {

    private static final String VERSION = resolve();

    private Version() {
    }

    private static String resolve() {
        Package pkg = Version.class.getPackage();
        if (pkg != null) {
            String v = pkg.getImplementationVersion();
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "dev";
    }

    /**
     * @return 版本号，例如 {@code 1.0.0} 或 {@code dev}
     */
    public static String get() {
        return VERSION;
    }
}
