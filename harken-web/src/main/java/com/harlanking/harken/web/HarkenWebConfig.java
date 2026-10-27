package com.harlanking.harken.web;

import com.harlanking.harken.core.catalog.RiskLevel;
import com.harlanking.harken.web.auth.Role;
import com.harlanking.harken.web.auth.SignatureVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * HTTP 层的配置，纯 POJO，不依赖任何框架。
 *
 * <p>之所以不用 record：这个对象要被 Spring Boot 的 {@code @ConfigurationProperties}
 * 绑定，绑定靠 setter。框架无关的部分（{@code harken-web}）只消费 {@link #build()} 之后的结果。
 *
 * <h2>默认值的选择</h2>
 * <p>每一个默认值都选「更严」的那个：
 * <ul>
 *   <li>只允许回环地址 —— 想对外必须先改配置，而不是「默认对外，记得改」</li>
 *   <li>没有配置账号时，<b>生成一个随机密码并打印在启动日志里</b>，
 *       而不是「没有账号就等于不校验」。前者是开箱即用，后者是敞开大门。</li>
 *   <li>没有配置 AI Token 时，AI 端点<b>根本不注册</b></li>
 * </ul>
 *
 * @author harlan
 * @since 1.0.0
 */
public final class HarkenWebConfig {

    /** 默认基路径。 */
    public static final String DEFAULT_BASE_PATH = "/harken";

    /** 默认请求体上限：1MB。脚本再长也到不了这个量级。 */
    public static final int DEFAULT_MAX_BODY_BYTES = 1_048_576;

    private final String basePath;
    private final List<String> allowedIps;
    private final List<UserSpec> users;
    private final Duration sessionIdle;
    private final Duration sessionMax;
    private final int maxLoginFailures;
    private final Duration lockDuration;
    private final boolean generatePasswordIfNoUsers;
    private final String aiToken;
    private final String aiSigningSecret;
    private final int aiSignatureWindowSeconds;
    private final int maxBodyBytes;
    private final boolean trustForwardedFor;
    private final String environment;
    private final WebMode mode;
    private final RiskLevel maxRiskForRestricted;

    private HarkenWebConfig(Builder b) {
        this.environment = b.environment == null ? "" : b.environment.trim();
        this.basePath = normalizeBasePath(b.basePath);
        this.allowedIps = List.copyOf(b.allowedIps);
        this.users = List.copyOf(b.users);
        this.sessionIdle = requirePositive(b.sessionIdle, "sessionIdle");
        this.sessionMax = requirePositive(b.sessionMax, "sessionMax");
        this.maxLoginFailures = b.maxLoginFailures;
        this.lockDuration = requirePositive(b.lockDuration, "lockDuration");
        this.generatePasswordIfNoUsers = b.generatePasswordIfNoUsers;
        this.aiToken = b.aiToken == null ? "" : b.aiToken.trim();
        this.aiSigningSecret = b.aiSigningSecret == null ? "" : b.aiSigningSecret.trim();
        this.aiSignatureWindowSeconds = b.aiSignatureWindowSeconds;
        this.maxBodyBytes = b.maxBodyBytes;
        this.trustForwardedFor = b.trustForwardedFor;
        this.mode = b.mode == null ? WebMode.FULL : b.mode;
        this.maxRiskForRestricted = b.maxRiskForRestricted == null ? RiskLevel.READ : b.maxRiskForRestricted;
        if (maxLoginFailures < 1) {
            throw new IllegalArgumentException("maxLoginFailures 必须为正");
        }
        if (aiSignatureWindowSeconds < 1) {
            throw new IllegalArgumentException("aiSignatureWindowSeconds 必须为正");
        }
        if (maxBodyBytes < 1024) {
            throw new IllegalArgumentException("maxBodyBytes 不得小于 1024");
        }
    }

    private static String normalizeBasePath(String raw) {
        String path = raw == null || raw.isBlank() ? DEFAULT_BASE_PATH : raw.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static Duration requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正");
        }
        return d;
    }

    /**
     * 一条账号配置（尚未解析密码里的 {@code env:} 引用）。
     *
     * @param username 用户名
     * @param password 密码原文，支持 {@code env:VAR} / PBKDF2 哈希串 / 明文
     * @param role     角色名，例如 {@code ADMIN}
     */
    public record UserSpec(String username, String password, String role) {

        /**
         * @throws IllegalArgumentException 字段缺失
         */
        public UserSpec {
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("用户名不能为空");
            }
            if (password == null || password.isEmpty()) {
                throw new IllegalArgumentException("用户 " + username + " 的密码不能为空");
            }
            Role.parse(role);
        }

        /**
         * @return 解析后的角色
         */
        public Role parsedRole() {
            return Role.parse(role);
        }
    }

    /**
     * @return 基路径，例如 {@code /harken}
     */
    public String basePath() {
        return basePath;
    }

    /**
     * @return IP 白名单，支持精确 IP、IPv4 CIDR、{@code *}
     */
    public List<String> allowedIps() {
        return allowedIps;
    }

    /**
     * @return 账号列表
     */
    public List<UserSpec> users() {
        return users;
    }

    /**
     * @return 会话空闲超时
     */
    public Duration sessionIdle() {
        return sessionIdle;
    }

    /**
     * @return 会话绝对上限
     */
    public Duration sessionMax() {
        return sessionMax;
    }

    /**
     * @return 连续失败多少次锁定
     */
    public int maxLoginFailures() {
        return maxLoginFailures;
    }

    /**
     * @return 锁定时长
     */
    public Duration lockDuration() {
        return lockDuration;
    }

    /**
     * @return 未配置账号时是否生成随机密码
     */
    public boolean generatePasswordIfNoUsers() {
        return generatePasswordIfNoUsers;
    }

    /**
     * @return AI 通道 Token 原文（可能带 {@code env:} 前缀）；空串表示 AI 通道不启用
     */
    public String aiToken() {
        return aiToken;
    }

    /**
     * AI 通道的请求签名密钥（SR-04）。支持 {@code env:VAR}。
     *
     * <p><b>配了就一定要求签名，没有「配了但可选」的模式。</b> 这一点是刻意的：
     * 允许「配了密钥但仍接受裸 Token」的配置，等于给出一个「我以为开了」的假象，
     * 比根本不配更危险 —— 出事时你会以为自己有签名保护。
     *
     * <p>空串表示不要求签名（仅靠 Bearer Token）。这与「没配 Token 就不注册 AI 端点」
     * 是同一套思路：要么完整，要么不存在。
     *
     * @return 签名密钥原文（可能带 {@code env:} 前缀）；空串表示不启用签名
     */
    public String aiSigningSecret() {
        return aiSigningSecret;
    }

    /**
     * 签名时间窗（秒），默认 {@value com.harlanking.harken.web.auth.SignatureVerifier#DEFAULT_WINDOW_SECONDS}。
     *
     * <p>窗口越长越省事（对时钟偏差更宽容），也越容易被重放。60 秒是
     * 「NTP 正常同步的机器之间足够」与「抓包后重放的价值窗口很短」的折中。
     *
     * @return 时间窗秒数
     */
    public int aiSignatureWindowSeconds() {
        return aiSignatureWindowSeconds;
    }

    /**
     * @return 请求体字节上限
     */
    public int maxBodyBytes() {
        return maxBodyBytes;
    }

    /**
     * 是否信任 {@code X-Forwarded-For}。
     *
     * <p><b>默认关闭。</b> 这个头是客户端可以随便伪造的：开启后任何能连上端口的人
     * 都可以自称 {@code 127.0.0.1} 绕过 IP 白名单。
     *
     * <p>只有一种情况应该打开：Harken 前面有一层你自己控制的 nginx / 网关，
     * 且该代理<b>覆盖</b>（而不是透传）这个头。此时不打开的话，
     * 所有请求的来源都变成代理的地址，IP 白名单会变成「全放行」或「全拒绝」。
     *
     * @return {@code true} 表示按 {@code X-Forwarded-For} 的第一个地址判定来源
     */
    public boolean trustForwardedFor() {
        return trustForwardedFor;
    }

    /**
     * 环境名称，显示在控制台状态条上，例如 {@code prod-shanghai}。
     *
     * <p><b>默认为空串，而不是猜一个值。</b> 控制台的第一功能是「让人确认自己没连错机器」，
     * 猜一个 {@code dev} 出来正好把这件事搞砸：真在 prod 上时它照样显示 dev。
     * 空串在界面上显示为「未声明」，配错了至少是诚实的。
     *
     * @return 环境名称；空串表示未配置
     */
    public String environment() {
        return environment;
    }

    /**
     * 控制台模式。
     *
     * @return {@link WebMode#FULL} 或 {@link WebMode#RESTRICTED}
     */
    public WebMode mode() {
        return mode;
    }

    /**
     * 是否受限模式（{@code mode == RESTRICTED}）。
     *
     * <p>前端据此决定是否渲染编辑器、后端据此决定是否拒绝自由脚本。
     *
     * @return {@code true} 表示受限模式
     */
    public boolean restricted() {
        return mode == WebMode.RESTRICTED;
    }

    /**
     * 受限模式下允许出现的最高危险等级。
     *
     * @return 最高危险等级，默认 {@link RiskLevel#READ}
     */
    public RiskLevel maxRiskForRestricted() {
        return maxRiskForRestricted;
    }

    /**
     * @return 默认配置：只允许回环、无账号（会生成随机密码）、AI 通道关闭
     */
    public static HarkenWebConfig defaults() {
        return builder().build();
    }

    /**
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 配置构建器。
     *
     * @author harlan
     * @since 1.0.0
     */
    public static final class Builder {

        private String basePath = DEFAULT_BASE_PATH;
        private List<String> allowedIps = new ArrayList<>(List.of("127.0.0.1", "::1", "::ffff:127.0.0.1"));
        private List<UserSpec> users = new ArrayList<>();
        private Duration sessionIdle = Duration.ofMinutes(30);
        private Duration sessionMax = Duration.ofHours(8);
        private int maxLoginFailures = 5;
        private Duration lockDuration = Duration.ofMinutes(15);
        private boolean generatePasswordIfNoUsers = true;
        private String aiToken = "";
        private String aiSigningSecret = "";
        private int aiSignatureWindowSeconds = SignatureVerifier.DEFAULT_WINDOW_SECONDS;
        private int maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
        private boolean trustForwardedFor = false;
        private String environment = "";
        private WebMode mode = WebMode.FULL;
        private RiskLevel maxRiskForRestricted = RiskLevel.READ;

        /** 构造器。 */
        public Builder() {
        }

        /**
         * @param v 基路径
         * @return 自身
         */
        public Builder basePath(String v) {
            this.basePath = v;
            return this;
        }

        /**
         * @param v IP 白名单
         * @return 自身
         */
        public Builder allowedIps(List<String> v) {
            this.allowedIps = v == null ? new ArrayList<>() : new ArrayList<>(v);
            return this;
        }

        /**
         * @param v 账号列表
         * @return 自身
         */
        public Builder users(List<UserSpec> v) {
            this.users = v == null ? new ArrayList<>() : new ArrayList<>(v);
            return this;
        }

        /**
         * @param v 会话空闲超时（分钟）
         * @return 自身
         */
        public Builder sessionIdleMinutes(long v) {
            this.sessionIdle = Duration.ofMinutes(v);
            return this;
        }

        /**
         * @param v 会话绝对上限（小时）
         * @return 自身
         */
        public Builder sessionMaxHours(long v) {
            this.sessionMax = Duration.ofHours(v);
            return this;
        }

        /**
         * @param v 失败多少次锁定
         * @return 自身
         */
        public Builder maxLoginFailures(int v) {
            this.maxLoginFailures = v;
            return this;
        }

        /**
         * @param v 锁定时长（分钟）
         * @return 自身
         */
        public Builder lockMinutes(long v) {
            this.lockDuration = Duration.ofMinutes(v);
            return this;
        }

        /**
         * @param v 是否在无账号时生成随机密码
         * @return 自身
         */
        public Builder generatePasswordIfNoUsers(boolean v) {
            this.generatePasswordIfNoUsers = v;
            return this;
        }

        /**
         * @param v AI Token 原文
         * @return 自身
         */
        public Builder aiToken(String v) {
            this.aiToken = v;
            return this;
        }

        /**
         * 开启请求签名（SR-04）。传入非空密钥即生效，之后所有 AI 请求必须带签名头。
         *
         * @param v 共享密钥，支持 {@code env:VAR}
         * @return 自身
         */
        public Builder aiSigningSecret(String v) {
            this.aiSigningSecret = v;
            return this;
        }

        /**
         * @param seconds 签名时间窗（秒）
         * @return 自身
         */
        public Builder aiSignatureWindowSeconds(int seconds) {
            this.aiSignatureWindowSeconds = seconds;
            return this;
        }

        /**
         * @param v 请求体字节上限
         * @return 自身
         */
        public Builder maxBodyBytes(int v) {
            this.maxBodyBytes = v;
            return this;
        }

        /**
         * @param v 是否信任 X-Forwarded-For
         * @return 自身
         */
        public Builder trustForwardedFor(boolean v) {
            this.trustForwardedFor = v;
            return this;
        }

        /**
         * @param v 环境名称，显示在控制台状态条
         * @return 自身
         */
        public Builder environment(String v) {
            this.environment = v;
            return this;
        }

        /**
         * @param v 控制台模式：{@link WebMode#FULL} 或 {@link WebMode#RESTRICTED}
         * @return 自身
         */
        public Builder mode(WebMode v) {
            this.mode = v == null ? WebMode.FULL : v;
            return this;
        }

        /**
         * 字符串形式的 {@link #mode(WebMode)}，容错未知值（按 {@link WebMode#FULL} 处理）。
         *
         * @param v 例如 {@code "restricted"}
         * @return 自身
         */
        public Builder mode(String v) {
            this.mode = WebMode.parse(v);
            return this;
        }

        /**
         * 受限模式下允许出现的最高危险等级。超过它的脚本不进列表。
         *
         * <p>默认 {@link RiskLevel#READ}：受限模式默认只放查询脚本，{@code DANGEROUS}
         * 不出现，除非显式调高。
         *
         * @param v 最高危险等级；{@code null} 当 {@link RiskLevel#READ}
         * @return 自身
         */
        public Builder maxRiskForRestricted(RiskLevel v) {
            this.maxRiskForRestricted = v == null ? RiskLevel.READ : v;
            return this;
        }

        /**
         * @return 不可变配置
         */
        public HarkenWebConfig build() {
            return new HarkenWebConfig(this);
        }
    }
}
