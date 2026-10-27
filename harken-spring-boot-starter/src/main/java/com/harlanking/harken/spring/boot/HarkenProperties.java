package com.harlanking.harken.spring.boot;

import com.harlanking.harken.core.HarkenOptions;
import com.harlanking.harken.core.catalog.RiskLevel;
import com.harlanking.harken.jdbc.GuardPolicy;
import com.harlanking.harken.web.HarkenWebConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code harken.*} 配置项。
 *
 * <p>默认值的选择原则是「不配置也能安全运行」：
 * 超时 10 秒、写操作默认拒绝、只支持只读的 AI 通道必须要有事务保护。
 *
 * <pre>{@code
 * harken:
 *   enabled: true
 *   packages:
 *     - com.zm
 *   timeout-ms: 10000
 *   require-transaction-for-ai: true
 * }</pre>
 *
 * <p>注意这里<b>没有</b> {@code ai.read-only} 之类的开关。AI 通道的只读是
 * {@link com.harlanking.harken.core.api.ReadOnlyRequest} 这个类型带来的编译期属性，
 * 不是配置项。可关闭的只读等于没有只读。
 *
 * @author harlan
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "harken")
public class HarkenProperties {

    /** 是否启用。默认 {@code true}：加依赖即可用，但必须配合网络层限制。 */
    private boolean enabled = true;

    /**
     * 暴露给脚本的业务包白名单。
     *
     * <p><b>强烈建议配置。</b> 留空时只排除框架内部包（Spring / Apache / JDK 等），
     * 第三方库的 Bean（数据源、连接池、模板引擎）也会出现在补全列表里，制造噪音。
     */
    private List<String> packages = new ArrayList<>();

    /** 单次执行硬超时（毫秒）。 */
    private long timeoutMs = 10_000;

    /** 脚本源码字符数上限。 */
    private int maxScriptLength = 102_400;

    /** 返回值 UTF-8 字节上限。 */
    private int resultMaxBytes = 8_192;

    /** {@code println} 输出 UTF-8 字节上限。 */
    private int outputMaxBytes = 32_768;

    /** 无事务保护时是否拒绝 AI 通道脚本。默认 {@code true}，并且不建议改。 */
    private boolean requireTransactionForAi = true;

    /** 启动时是否打印 banner。 */
    private boolean printBanner = true;

    /**
     * 转成 core 的执行参数。
     *
     * @return 不可变参数对象
     */
    public HarkenOptions toOptions() {
        return new HarkenOptions(timeoutMs, maxScriptLength, resultMaxBytes,
                outputMaxBytes, requireTransactionForAi);
    }

    /** @return 是否启用 */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled 是否启用 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return 业务包白名单 */
    public List<String> getPackages() {
        return packages;
    }

    /** @param packages 业务包白名单 */
    public void setPackages(List<String> packages) {
        this.packages = packages == null ? new ArrayList<>() : packages;
    }

    /** @return 超时毫秒 */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /** @param timeoutMs 超时毫秒 */
    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /** @return 脚本长度上限 */
    public int getMaxScriptLength() {
        return maxScriptLength;
    }

    /** @param maxScriptLength 脚本长度上限 */
    public void setMaxScriptLength(int maxScriptLength) {
        this.maxScriptLength = maxScriptLength;
    }

    /** @return 返回值字节上限 */
    public int getResultMaxBytes() {
        return resultMaxBytes;
    }

    /** @param resultMaxBytes 返回值字节上限 */
    public void setResultMaxBytes(int resultMaxBytes) {
        this.resultMaxBytes = resultMaxBytes;
    }

    /** @return 输出字节上限 */
    public int getOutputMaxBytes() {
        return outputMaxBytes;
    }

    /** @param outputMaxBytes 输出字节上限 */
    public void setOutputMaxBytes(int outputMaxBytes) {
        this.outputMaxBytes = outputMaxBytes;
    }

    /** @return 无事务时是否拒绝 AI 只读 */
    public boolean isRequireTransactionForAi() {
        return requireTransactionForAi;
    }

    /** @param requireTransactionForAi 无事务时是否拒绝 AI 只读 */
    public void setRequireTransactionForAi(boolean requireTransactionForAi) {
        this.requireTransactionForAi = requireTransactionForAi;
    }

    /** @return 是否打印启动 banner */
    public boolean isPrintBanner() {
        return printBanner;
    }

    /** @param printBanner 是否打印启动 banner */
    public void setPrintBanner(boolean printBanner) {
        this.printBanner = printBanner;
    }

    // ------------------------------------------------------------------
    // HTTP 层
    // ------------------------------------------------------------------

    /** HTTP 层配置。 */
    private Web web = new Web();

    /** AI 通道配置。 */
    private Ai ai = new Ai();

    /** @return HTTP 层配置 */
    public Web getWeb() {
        return web;
    }

    /** @param web HTTP 层配置 */
    public void setWeb(Web web) {
        this.web = web == null ? new Web() : web;
    }

    /** @return AI 通道配置 */
    public Ai getAi() {
        return ai;
    }

    /** @param ai AI 通道配置 */
    public void setAi(Ai ai) {
        this.ai = ai == null ? new Ai() : ai;
    }

    /**
     * 转成 {@code harken-web} 的配置对象。
     *
     * @return 不可变配置
     */
    public HarkenWebConfig toWebConfig() {
        List<HarkenWebConfig.UserSpec> specs = web.users.stream()
                .map(u -> new HarkenWebConfig.UserSpec(u.getUsername(), u.getPassword(), u.getRole()))
                .toList();
        return HarkenWebConfig.builder()
                .basePath(web.basePath)
                .allowedIps(web.allowedIps)
                .users(specs)
                .sessionIdleMinutes(web.sessionIdleMinutes)
                .sessionMaxHours(web.sessionMaxHours)
                .maxLoginFailures(web.maxLoginFailures)
                .lockMinutes(web.lockMinutes)
                .generatePasswordIfNoUsers(web.generatePasswordIfNoUsers)
                .aiToken(ai.isEnabled() ? ai.token : "")
                // 签名密钥不受 ai.enabled 影响：关闭 AI 通道时 servlet 本来就不注册，
                // 这里再叠一层 && 只会让「开了 AI 却忘了开签名」这种事故更难被发现
                .aiSigningSecret(ai.signingSecret)
                .aiSignatureWindowSeconds(ai.signatureWindowSeconds)
                .maxBodyBytes(web.maxBodyBytes)
                .trustForwardedFor(web.trustForwardedFor)
                .environment(web.environment)
                .mode(web.mode)
                .maxRiskForRestricted(RiskLevel.parse(catalog.getMaxRisk()))
                .build();
    }

    /**
     * HTTP 层配置项。
     *
     * @author harlan
     * @since 1.0.0
     */
    public static class Web {

        /** 是否注册 HTTP 端点。关掉它 Harken 就只剩一个可编程的 Bean。 */
        private boolean enabled = true;

        /** 基路径。 */
        private String basePath = "/harken";

        /**
         * IP 白名单。默认只允许回环。
         *
         * <p>支持精确 IP、IPv4 CIDR（{@code 10.0.0.0/8}）、{@code *}（全放行）。
         * 留空等于全拒绝。
         */
        private List<String> allowedIps = new ArrayList<>(List.of("127.0.0.1", "::1", "::ffff:127.0.0.1"));

        /** 会话空闲超时（分钟）。 */
        private long sessionIdleMinutes = 30;

        /** 会话绝对上限（小时）。 */
        private long sessionMaxHours = 8;

        /** 连续登录失败多少次锁定。 */
        private int maxLoginFailures = 5;

        /** 锁定时长（分钟）。 */
        private long lockMinutes = 15;

        /** 未配置账号时是否生成随机密码并打到日志。关掉等于人类通道不可登录。 */
        private boolean generatePasswordIfNoUsers = true;

        /** 请求体上限（字节）。 */
        private int maxBodyBytes = HarkenWebConfig.DEFAULT_MAX_BODY_BYTES;

        /** 是否信任 {@code X-Forwarded-For}。仅在 Harken 前面有自己控制的代理时开启。 */
        private boolean trustForwardedFor = false;

        /**
         * 环境名称，显示在控制台顶部的状态条上，例如 {@code prod-shanghai}。
         *
         * <p>默认空串（界面上显示「未声明」）。刻意不从 {@code spring.profiles.active}
         * 猜一个：状态条存在的意义就是让人确认「我连的是这台机器」，猜出来的值
         * 在真出问题时一定是错的。
         */
        private String environment = "";

        /**
         * 控制台模式：{@code FULL}（自由编写）或 {@code RESTRICTED}（只能从预置目录选脚本）。
         *
         * <p>默认 {@code FULL}：保住「加依赖即可用」的体验。要锁定成受限模式显式配置
         * {@code harken.web.mode: RESTRICTED} 并配好 {@code harken.catalog.provider}。
         */
        private String mode = "FULL";

        /** 账号列表。 */
        private List<User> users = new ArrayList<>();

        /** @return 是否启用 */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled 是否启用 */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return 基路径 */
        public String getBasePath() {
            return basePath;
        }

        /** @param basePath 基路径 */
        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }

        /** @return IP 白名单 */
        public List<String> getAllowedIps() {
            return allowedIps;
        }

        /** @param allowedIps IP 白名单 */
        public void setAllowedIps(List<String> allowedIps) {
            this.allowedIps = allowedIps == null ? new ArrayList<>() : allowedIps;
        }

        /** @return 会话空闲超时（分钟） */
        public long getSessionIdleMinutes() {
            return sessionIdleMinutes;
        }

        /** @param v 会话空闲超时（分钟） */
        public void setSessionIdleMinutes(long v) {
            this.sessionIdleMinutes = v;
        }

        /** @return 会话绝对上限（小时） */
        public long getSessionMaxHours() {
            return sessionMaxHours;
        }

        /** @param v 会话绝对上限（小时） */
        public void setSessionMaxHours(long v) {
            this.sessionMaxHours = v;
        }

        /** @return 失败锁定阈值 */
        public int getMaxLoginFailures() {
            return maxLoginFailures;
        }

        /** @param v 失败锁定阈值 */
        public void setMaxLoginFailures(int v) {
            this.maxLoginFailures = v;
        }

        /** @return 锁定时长（分钟） */
        public long getLockMinutes() {
            return lockMinutes;
        }

        /** @param v 锁定时长（分钟） */
        public void setLockMinutes(long v) {
            this.lockMinutes = v;
        }

        /** @return 是否生成临时密码 */
        public boolean isGeneratePasswordIfNoUsers() {
            return generatePasswordIfNoUsers;
        }

        /** @param v 是否生成临时密码 */
        public void setGeneratePasswordIfNoUsers(boolean v) {
            this.generatePasswordIfNoUsers = v;
        }

        /** @return 请求体上限 */
        public int getMaxBodyBytes() {
            return maxBodyBytes;
        }

        /** @param v 请求体上限 */
        public void setMaxBodyBytes(int v) {
            this.maxBodyBytes = v;
        }

        /** @return 是否信任 X-Forwarded-For */
        public boolean isTrustForwardedFor() {
            return trustForwardedFor;
        }

        /** @param v 是否信任 X-Forwarded-For */
        public void setTrustForwardedFor(boolean v) {
            this.trustForwardedFor = v;
        }

        /** @return 环境名称，显示在控制台状态条 */
        public String getEnvironment() {
            return environment;
        }

        /** @param v 环境名称 */
        public void setEnvironment(String v) {
            this.environment = v;
        }

        /** @return 控制台模式 */
        public String getMode() {
            return mode;
        }

        /** @param v 控制台模式 */
        public void setMode(String v) {
            this.mode = v == null ? "FULL" : v;
        }

        /** @return 账号列表 */
        public List<User> getUsers() {
            return users;
        }

        /** @param users 账号列表 */
        public void setUsers(List<User> users) {
            this.users = users == null ? new ArrayList<>() : users;
        }
    }

    /**
     * 一条账号配置。
     *
     * @author harlan
     * @since 1.0.0
     */
    public static class User {

        /** 用户名。 */
        private String username;

        /**
         * 密码。三种写法：
         * <ul>
         *   <li>{@code env:VAR} —— 读环境变量（生产推荐）</li>
         *   <li>{@code pbkdf2-sha256$...} —— 哈希串（用 PasswordHash 生成）</li>
         *   <li>明文 —— 仅测试，启动会 WARN</li>
         * </ul>
         */
        private String password;

        /** 角色：{@code ADMIN} / {@code OPERATOR} / {@code READONLY}。默认 {@code OPERATOR}。 */
        private String role = "OPERATOR";

        /** @return 用户名 */
        public String getUsername() {
            return username;
        }

        /** @param username 用户名 */
        public void setUsername(String username) {
            this.username = username;
        }

        /** @return 密码配置原文 */
        public String getPassword() {
            return password;
        }

        /** @param password 密码配置原文 */
        public void setPassword(String password) {
            this.password = password;
        }

        /** @return 角色名 */
        public String getRole() {
            return role;
        }

        /** @param role 角色名 */
        public void setRole(String role) {
            this.role = role;
        }
    }

    // ------------------------------------------------------------------
    // 写入守卫
    // ------------------------------------------------------------------

    /** 预置脚本目录配置。 */
    private Catalog catalog = new Catalog();

    /** @return 预置脚本目录配置 */
    public Catalog getCatalog() {
        return catalog;
    }

    /** @param catalog 预置脚本目录配置 */
    public void setCatalog(Catalog catalog) {
        this.catalog = catalog == null ? new Catalog() : catalog;
    }

    /**
     * 预置脚本目录（受限模式的数据来源）配置。
     *
     * <pre>{@code
     * harken:
     *   catalog:
     *     provider: classpath     # classpath | file | none
     *     file: ./scripts/catalog.json
     *     max-risk: READ           # 受限模式允许出现的最高危险等级
     * }</pre>
     *
     * @author harlan
     * @since 1.0.0
     */
    public static class Catalog {

        /** 目录来源：classpath（jar 内 harken-scripts/*.json）| file（外部文件/目录）| none（空目录）。 */
        private String provider = "none";

        /** provider=file 时的路径（文件或目录）。 */
        private String file = "";

        /** 预留：是否热加载（FileScriptCatalog 默认热加载，这里仅作文档占位）。 */
        private boolean allowHotReload = true;

        /** 受限模式允许出现的最高危险等级：READ | WRITE | DANGEROUS。 */
        private String maxRisk = "READ";

        /** @return 目录来源 */
        public String getProvider() {
            return provider;
        }

        /** @param v 目录来源 */
        public void setProvider(String v) {
            provider = v == null ? "none" : v;
        }

        /** @return 外部文件路径 */
        public String getFile() {
            return file;
        }

        /** @param v 外部文件路径 */
        public void setFile(String v) {
            file = v == null ? "" : v;
        }

        /** @return 是否热加载 */
        public boolean isAllowHotReload() {
            return allowHotReload;
        }

        /** @param v 是否热加载 */
        public void setAllowHotReload(boolean v) {
            allowHotReload = v;
        }

        /** @return 最高危险等级字符串 */
        public String getMaxRisk() {
            return maxRisk;
        }

        /** @param v 最高危险等级字符串 */
        public void setMaxRisk(String v) {
            maxRisk = v == null ? "READ" : v;
        }
    }

    /** 写入守卫配置。 */
    private Guard guard = new Guard();

    /** 审计配置。 */
    private Audit audit = new Audit();

    /** @return 写入守卫配置 */
    public Guard getGuard() {
        return guard;
    }

    /** @param guard 写入守卫配置 */
    public void setGuard(Guard guard) {
        this.guard = guard == null ? new Guard() : guard;
    }

    /** @return 审计配置 */
    public Audit getAudit() {
        return audit;
    }

    /** @param audit 审计配置 */
    public void setAudit(Audit audit) {
        this.audit = audit == null ? new Audit() : audit;
    }

    /**
     * 转成 {@code harken-jdbc} 的策略对象。
     *
     * <p>返回 {@link GuardPolicy#passThrough()} 表示「不包装 DataSource」——
     * 没配策略时不碰应用的 JDBC，这条选择写在 {@code GuardPolicy} 的注释里。
     *
     * @return 不可变策略
     */
    public GuardPolicy toGuardPolicy() {
        if (!guard.enabled) {
            return GuardPolicy.passThrough();
        }
        return new GuardPolicy(guard.maxAffectedRows, guard.denyTables, guard.denyDdl);
    }

    /**
     * 写入守卫配置（影响行数闸、关键表黑名单、DDL 策略）。
     *
     * <pre>{@code
     * harken:
     *   guard:
     *     enabled: true
     *     max-affected-rows: 1000
     *     deny-tables:
     *       - supply_order
     *       - "*_payment"
     *     deny-ddl: false
     * }</pre>
     *
     * @author harlan
     * @since 1.0.0
     */
    public static class Guard {

        /** 是否启用。关掉等于完全不碰应用的 JDBC。 */
        private boolean enabled = true;

        /**
         * 单次执行累计影响行数上限，{@code 0} 表示不限。
         *
         * <p>不限是默认值：包装 {@code DataSource} 会改变全应用的 JDBC 链路，
         * 没有明确需求时不该替使用者做这个决定。但一旦配置了，
         * 超过阈值的执行会在<b>执行过程中</b>抛异常并回滚。
         */
        private long maxAffectedRows = 0;

        /**
         * 关键表黑名单：命中即拒绝该<b>写</b>语句（读不受影响）。
         *
         * <p>支持精确名 {@code supply_order}、头部通配 {@code *_order}、尾部通配 {@code tmp_*}。
         */
        private List<String> denyTables = new ArrayList<>();

        /** 是否拒绝 DDL。默认关：紧急加个索引是合法操作，但可以一键锁死。 */
        private boolean denyDdl = false;

        /** @return 是否启用 */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled 是否启用 */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return 行数上限，0 表示不限 */
        public long getMaxAffectedRows() {
            return maxAffectedRows;
        }

        /** @param v 行数上限 */
        public void setMaxAffectedRows(long v) {
            this.maxAffectedRows = v;
        }

        /** @return 关键表黑名单 */
        public List<String> getDenyTables() {
            return denyTables;
        }

        /** @param v 关键表黑名单 */
        public void setDenyTables(List<String> v) {
            this.denyTables = v == null ? new ArrayList<>() : v;
        }

        /** @return 是否拒绝 DDL */
        public boolean isDenyDdl() {
            return denyDdl;
        }

        /** @param v 是否拒绝 DDL */
        public void setDenyDdl(boolean v) {
            this.denyDdl = v;
        }
    }

    /**
     * 审计配置。
     *
     * @author harlan
     * @since 1.0.0
     */
    public static class Audit {

        /** 数据库审计。 */
        private Jdbc jdbc = new Jdbc();

        /** 内存审计。 */
        private Memory memory = new Memory();

        /** @return 数据库审计配置 */
        public Jdbc getJdbc() {
            return jdbc;
        }

        /** @param jdbc 数据库审计配置 */
        public void setJdbc(Jdbc jdbc) {
            this.jdbc = jdbc == null ? new Jdbc() : jdbc;
        }

        /** @return 内存审计配置 */
        public Memory getMemory() {
            return memory;
        }

        /** @param memory 内存审计配置 */
        public void setMemory(Memory memory) {
            this.memory = memory == null ? new Memory() : memory;
        }

        /**
         * 内存审计（环形缓冲）。
         *
         * <p><b>默认开启</b>（计划 §8.1「默认 memory + slf4j」）：控制台的「最近执行」
         * 面板因此零配置可用。它<b>不取代</b>日志审计 —— 开启后日志照写，
         * 因为内存缓冲重启即失、多实例各一份，当不了合规证据。
         *
         * <pre>{@code
         * harken:
         *   audit:
         *     memory:
         *       enabled: true    # 默认就是 true，写出来只是表明态度
         *       capacity: 200
         * }</pre>
         *
         * @author harlan
         * @since 1.0.0
         */
        public static class Memory {

            /** 是否启用内存审计。默认开启。 */
            private boolean enabled = true;

            /**
             * 保留条数。
             *
             * <p>200 条的取舍：够覆盖「最近一小时的试跑」，又不会让一个面板吃掉几十 MB 内存。
             * 脚本原文在内存副本里另截到 4K 字符，所以不必按脚本上限估内存。
             */
            private int capacity = 200;

            /** @return 是否启用 */
            public boolean isEnabled() {
                return enabled;
            }

            /** @param enabled 是否启用 */
            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            /** @return 保留条数 */
            public int getCapacity() {
                return capacity;
            }

            /** @param capacity 保留条数，最小 1 */
            public void setCapacity(int capacity) {
                this.capacity = capacity;
            }
        }

        /**
         * 数据库审计表。
         *
         * <p>默认关闭：默认写日志（零建表、零权限要求），需要可查询的审计时再打开。
         *
         * <pre>{@code
         * harken:
         *   audit:
         *     jdbc:
         *       enabled: true
         *       table: harken_audit
         *       auto-create: true
         * }</pre>
         *
         * @author harlan
         * @since 1.0.0
         */
        public static class Jdbc {

            /** 是否启用数据库审计。 */
            private boolean enabled = false;

            /** 表名，可带 schema 前缀。 */
            private String table = "harken_audit";

            /** 是否自动建表。生产环境通常由 DBA 建表后关掉它。 */
            private boolean autoCreate = true;

            /** @return 是否启用 */
            public boolean isEnabled() {
                return enabled;
            }

            /** @param enabled 是否启用 */
            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            /** @return 表名 */
            public String getTable() {
                return table;
            }

            /** @param table 表名 */
            public void setTable(String table) {
                this.table = table;
            }

            /** @return 是否自动建表 */
            public boolean isAutoCreate() {
                return autoCreate;
            }

            /** @param autoCreate 是否自动建表 */
            public void setAutoCreate(boolean autoCreate) {
                this.autoCreate = autoCreate;
            }
        }
    }

    /**
     * AI 通道配置。
     *
     * <p>没有 Token 就没有这个通道 —— 端点根本不会注册。这不是「留了个后门但默认关着」，
     * 而是「没配 = 不存在」。
     *
     * @author harlan
     * @since 1.0.0
     */
    public static class Ai {

        /** 是否启用（仍需配置 token 才真正生效）。 */
        private boolean enabled = true;

        /** Bearer Token。支持 {@code env:VAR}。留空 = AI 通道不注册。 */
        private String token = "";

        /**
         * 请求签名密钥（SR-04）。支持 {@code env:VAR}。
         *
         * <p>留空 = 只校验 Token，不要求签名（默认）；非空 = <b>强制</b>所有 AI 请求带
         * {@code X-Harken-Timestamp} / {@code X-Harken-Nonce} / {@code X-Harken-Signature}。
         * 没有「配了但仍可选」的模式。
         */
        private String signingSecret = "";

        /** 签名时间窗（秒）。默认 60。 */
        private int signatureWindowSeconds = 60;

        /** @return 是否启用 */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled 是否启用 */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return Token 原文 */
        public String getToken() {
            return token;
        }

        /** @param token Token 原文 */
        public void setToken(String token) {
            this.token = token;
        }

        /** @return 签名密钥原文；非空即强制所有 AI 请求带签名 */
        public String getSigningSecret() {
            return signingSecret;
        }

        /** @param signingSecret 签名密钥，支持 {@code env:VAR} */
        public void setSigningSecret(String signingSecret) {
            this.signingSecret = signingSecret;
        }

        /** @return 签名时间窗（秒） */
        public int getSignatureWindowSeconds() {
            return signatureWindowSeconds;
        }

        /** @param signatureWindowSeconds 签名时间窗（秒） */
        public void setSignatureWindowSeconds(int signatureWindowSeconds) {
            this.signatureWindowSeconds = signatureWindowSeconds;
        }
    }
}
