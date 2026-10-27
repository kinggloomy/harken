package com.harlanking.harken.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 配置文件权限检查：回答一个问题 —— <b>放着密码的那个文件，别人能不能读</b>。
 *
 * <h2>为什么值得检查</h2>
 *
 * <p>Harken 的账号密码写在配置里（{@code harken.web.users[*].password}，哈希或明文），
 * AI 的 Token 也一样。配置文件的权限一旦是 {@code 644}，同一台机器上的任何用户
 * 都能把它读走 —— 这是最常见、也最容易被忽略的一种泄漏：应用本身没有漏洞，
 * 数据却已经在别人手里了。
 *
 * <p>所以启动时报一句 WARN，比事后审计里发现「有个陌生账号在跑脚本」便宜得多。
 *
 * <h2>为什么只提示不拒绝启动</h2>
 *
 * <p>目录归属、容器镜像、Kubernetes Secret 挂载都会影响权限，而 Harken 看不到这些上下文。
 * 「因为 644 就不让应用启动」会造成比泄漏更常见的故障：半夜发布失败、运维被迫改配置、
 * 最后干脆把这个检查关掉。<b>提示到位、由人决定</b>，是这个工具一贯的做法。
 *
 * <h2>判断标准</h2>
 *
 * <p>只允许 {@code OWNER_READ} 与 {@code OWNER_WRITE}（即 {@code 600}）。
 * 出现任何 group / others 位，或属主执行位，都算过宽 —— 配置文件不需要可执行。
 *
 * <h2>平台差异</h2>
 *
 * <p>Windows 没有 POSIX 权限模型，这里返回 {@link Status#UNSUPPORTED}，
 * <b>而不是假装检查通过</b>。调用方据此保持沉默，不要打出「权限正常」这种结论。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class ConfigFilePermissions {

    /** 允许出现的权限位：{@code 600}。 */
    private static final Set<PosixFilePermission> ALLOWED =
            Collections.unmodifiableSet(EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));

    private static final String OWNER_PREFIX = "OWNER_";
    private static final String GROUP_PREFIX = "GROUP_";
    private static final String OTHERS_PREFIX = "OTHERS_";

    private ConfigFilePermissions() {
    }

    /**
     * 检查结果的状态。
     *
     * @author harlan
     * @since 1.0.0
     */
    public enum Status {

        /** 权限是 {@code 600}（或更严），没有问题。 */
        OK,

        /** 权限比 {@code 600} 宽，需要提示使用者。 */
        TOO_OPEN,

        /** 文件不存在 —— 通常意味着配置来自 jar 内部，或路径已失效。 */
        MISSING,

        /** 路径存在但不是普通文件（目录、设备、命名管道等）。 */
        NOT_A_FILE,

        /** 当前平台没有 POSIX 权限模型（典型：Windows），无从判断。 */
        UNSUPPORTED
    }

    /**
     * 一次检查的结果。
     *
     * @param status      检查结论
     * @param path        被检查的路径，可能为 {@code null}（例如传入了 {@code null}）
     * @param permissions 实际读到的权限位；未测量时为空集
     * @param detail      给人看的一句话说明，永不为 {@code null}
     * @author harlan
     * @since 1.0.0
     */
    public record Result(Status status, Path path, Set<PosixFilePermission> permissions, String detail) {

        /**
         * 紧凑构造器：保证 {@code permissions} 不可变、{@code detail} 不为 {@code null}。
         */
        public Result {
            permissions = permissions == null || permissions.isEmpty()
                    ? Set.of()
                    : Collections.unmodifiableSet(EnumSet.copyOf(permissions));
            detail = detail == null ? "" : detail;
        }

        /**
         * 是否需要提示使用者。
         *
         * @return {@code true} 表示权限过宽
         */
        public boolean shouldWarn() {
            return status == Status.TOO_OPEN;
        }

        /**
         * 权限位是否真的读到了（只有 {@link Status#OK} 与 {@link Status#TOO_OPEN} 为真）。
         *
         * @return {@code true} 表示 {@link #permissions()} 是真实值，而不是空集占位
         */
        public boolean measured() {
            return status == Status.OK || status == Status.TOO_OPEN;
        }

        /**
         * 权限的八进制写法，例如 {@code 600} / {@code 644}。未测量时返回 {@code "----"}。
         *
         * @return 三位八进制字符串，或 {@code "----"}
         */
        public String octal() {
            return measured() ? toOctal(permissions) : "----";
        }
    }

    /**
     * 检查一个文件的权限。
     *
     * <p>本方法不抛异常：所有失败都变成 {@link Status} 的一个取值 —— 调用方在启动路径上，
     * 不该为了「顺手检查一下」而拿到一个异常。
     *
     * @param path 文件路径，可为 {@code null}
     * @return 检查结果，永不为 {@code null}
     */
    public static Result check(Path path) {
        if (path == null) {
            return new Result(Status.MISSING, null, Set.of(), "路径为空");
        }
        if (!Files.exists(path)) {
            return new Result(Status.MISSING, path, Set.of(), "文件不存在（可能来自 jar 内部，无法检查）");
        }
        if (!Files.isRegularFile(path)) {
            return new Result(Status.NOT_A_FILE, path, Set.of(), "不是普通文件");
        }

        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException e) {
            // Windows 走这里。不能返回 OK —— 那等于说「我检查过了，没问题」，而事实是没检查
            return new Result(Status.UNSUPPORTED, path, Set.of(),
                    "当前平台不支持 POSIX 权限（Windows 无此模型），无法判断");
        } catch (IOException | SecurityException e) {
            return new Result(Status.UNSUPPORTED, path, Set.of(),
                    "读取权限失败：" + e.getClass().getSimpleName());
        }

        Set<PosixFilePermission> offenders = EnumSet.noneOf(PosixFilePermission.class);
        offenders.addAll(permissions);
        offenders.removeAll(ALLOWED);

        if (offenders.isEmpty()) {
            return new Result(Status.OK, path, permissions, "权限 " + toOctal(permissions) + "，仅属主可读写");
        }
        return new Result(Status.TOO_OPEN, path, permissions,
                "权限 " + toOctal(permissions) + "，过宽位：" + describe(offenders));
    }

    /**
     * 把权限位转成三位八进制，例如 {@code 644}。
     *
     * @param permissions 权限集合，可为 {@code null}
     * @return 三位八进制字符串；入参为空时返回 {@code 000}
     */
    public static String toOctal(Set<PosixFilePermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return "000";
        }
        int[] digits = new int[3];
        for (PosixFilePermission p : permissions) {
            digits[slotOf(p.name())] |= digitOf(p.name());
        }
        return "" + digits[0] + digits[1] + digits[2];
    }

    /**
     * 把过宽的权限位翻译成人话。区分 owner / group / others 是有用的：
     * group 位通常来自 umask，others 位才是真正危险的。
     *
     * @param offenders 过宽位
     * @return 例如 {@code group:READ, others:READ}
     */
    private static String describe(Set<PosixFilePermission> offenders) {
        List<String> parts = new ArrayList<>(3);
        addIfAny(parts, offenders, OWNER_PREFIX, "owner");
        addIfAny(parts, offenders, GROUP_PREFIX, "group");
        addIfAny(parts, offenders, OTHERS_PREFIX, "others");
        return String.join(", ", parts);
    }

    private static void addIfAny(List<String> parts, Set<PosixFilePermission> perms,
                                 String prefix, String label) {
        StringBuilder sb = new StringBuilder();
        for (PosixFilePermission p : perms) {
            if (p.name().startsWith(prefix)) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(p.name().substring(prefix.length()));
            }
        }
        if (sb.length() > 0) {
            parts.add(label + ":" + sb);
        }
    }

    /** @param name 枚举名，例如 {@code OTHERS_READ} @return 0=owner / 1=group / 2=others */
    private static int slotOf(String name) {
        if (name.startsWith(OWNER_PREFIX)) {
            return 0;
        }
        return name.startsWith(GROUP_PREFIX) ? 1 : 2;
    }

    /** @param name 枚举名 @return READ=4 / WRITE=2 / EXECUTE=1 */
    private static int digitOf(String name) {
        if (name.endsWith("_READ")) {
            return 4;
        }
        return name.endsWith("_WRITE") ? 2 : 1;
    }
}
