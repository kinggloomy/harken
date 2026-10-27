package com.harlanking.harken.web.auth;

import com.harlanking.harken.web.HarkenWebConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人类通道的认证中心：登录、会话、失败锁定、凭据重载。
 *
 * <h2>三条刻意的设计</h2>
 * <ol>
 *   <li><b>用户不存在与密码错误返回完全一样的结果</b>，耗时也一样
 *       （找不到用户时照样跑一次 PBKDF2）。否则「哪种用户存在」会从响应差异里漏出去。</li>
 *   <li><b>没有配置任何账号时，生成一个随机密码并打到日志里</b>，而不是放行。
 *       前者的效果是「开箱即用，去看一眼日志」；后者是「忘了配就等于没有认证」。
 *       随机密码只存在内存里，重启即变。</li>
 *   <li><b>重载凭据会踢掉所有会话。</b> 改密码不踢会话，等于改密码不生效。</li>
 * </ol>
 *
 * <h2>「重载」到底重载什么</h2>
 * <p>只重新解析一遍 {@code env:} 引用（即重新读环境变量），然后清空会话。
 * <b>不会</b>重新读取 yml —— 那是配置框架的职责，Harken 不去猜。
 * 所以「轮换密码」的正确姿势是：把密码放环境变量 → 改环境变量 → 调
 * {@code POST /harken/auth/reload}。这条限制写在文档里，不让人误以为改了 yml 就能热生效。
 *
 * <p>本类线程安全。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 生成的随机密码长度。足够长到自己都懒得抄错。 */
    private static final int GENERATED_LENGTH = 24;

    /**
     * 排除容易看错的字符：0/O、1/l/I、2/Z、5/S。
     * 这个密码是要人从日志里抄到浏览器里的，抄错一次就会怀疑是工具坏了。
     */
    private static final String ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ3456789-_";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 用一个固定的假装哈希来做「用户不存在」时的等效耗时。 */
    private static final String DUMMY_HASH = PasswordHash.hash("harken-dummy-password-for-timing", 120_000);

    private final HarkenWebConfig config;
    private final SessionStore sessions;
    private final LoginThrottle throttle;
    private final List<HarkenWebConfig.UserSpec> userSpecs;

    /** 用户名 → 凭据。重载时整体替换，读路径无锁。 */
    private volatile Map<String, Credential> credentials;

    /** 解析后的 AI Token；空串表示 AI 通道未启用。 */
    private volatile String aiToken;

    /** 请求签名校验器；未配密钥时是 disabled 单例。 */
    private volatile SignatureVerifier signatureVerifier;

    /**
     * @param config HTTP 配置
     */
    public AuthService(HarkenWebConfig config) {
        this.config = config;
        this.userSpecs = config.users();
        this.sessions = new SessionStore(config.sessionIdle(), config.sessionMax());
        this.throttle = new LoginThrottle(config.maxLoginFailures(), config.lockDuration());
        this.credentials = loadCredentials(userSpecs);
        this.aiToken = resolveAiToken(config.aiToken());
        this.signatureVerifier = buildVerifier(config);
        announceGeneratedPassword();
    }

    /**
     * 构造签名校验器。
     *
     * <p>密钥同样支持 {@code env:VAR}：签名密钥是「共享密钥」，绝不能明文躺在 yml 里进 Git。
     * 解析失败时<b>不降级为「不要求签名」</b>，而是打 ERROR 并保持一个「永远拒绝」的校验器 ——
     * 配置坏了应该连不上，而不是悄悄变宽松。
     */
    private SignatureVerifier buildVerifier(HarkenWebConfig cfg) {
        String raw = cfg.aiSigningSecret();
        if (raw == null || raw.isBlank()) {
            return SignatureVerifier.disabled();
        }
        String secret;
        try {
            secret = SecretResolver.resolve(raw);
        } catch (RuntimeException e) {
            log.error("Harken: AI 签名密钥无法解析，AI 通道将拒绝所有请求。原因：{}", e.getMessage());
            // 空密钥会让 enabled()=false（等于不校验），所以这里用一个占位密钥：
            // 调用方永远算不出正确签名，效果是「全部拒绝」，符合「配置坏了就别通」的原则
            return new SignatureVerifier("\u0000unresolvable", cfg.aiSignatureWindowSeconds());
        }
        if (secret.isBlank()) {
            log.error("Harken: AI 签名密钥解析结果为空，AI 通道将拒绝所有请求。");
            return new SignatureVerifier("\u0000unresolvable", cfg.aiSignatureWindowSeconds());
        }
        log.info("Harken: AI 请求签名已启用（时间窗 {} 秒）。", cfg.aiSignatureWindowSeconds());
        return new SignatureVerifier(secret, cfg.aiSignatureWindowSeconds());
    }

    /**
     * @return 请求签名校验器（未配置密钥时 {@code enabled() == false}）
     */
    public SignatureVerifier signatureVerifier() {
        return signatureVerifier;
    }

    // ------------------------------------------------------------------
    // 凭据装载
    // ------------------------------------------------------------------

    private Map<String, Credential> loadCredentials(List<HarkenWebConfig.UserSpec> specs) {
        Map<String, Credential> map = new LinkedHashMap<>();
        for (HarkenWebConfig.UserSpec spec : specs) {
            try {
                String secret = SecretResolver.resolve(spec.password());
                map.put(spec.username(), new Credential(spec.username(), secret, spec.parsedRole()));
                if (!PasswordHash.isHashed(secret)) {
                    log.warn("Harken: 用户 {} 使用明文密码配置。生产环境请改用 "
                            + "env:变量名 或 PBKDF2 哈希串（见 PasswordHash 工具）。", spec.username());
                }
            } catch (RuntimeException e) {
                // 单个账号配置坏了不能拖垮整个控制台，但必须显眼
                log.error("Harken: 用户 {} 的密码配置无法解析，该账号已被禁用。原因：{}",
                        spec.username(), e.getMessage());
            }
        }
        if (map.isEmpty() && config.generatePasswordIfNoUsers()) {
            String generated = randomPassword();
            map.put("admin", new Credential("admin", generated, Role.ADMIN));
            log.warn("""

                    ============================================================
                     Harken 没有配置任何账号，已生成临时管理员账号：
                       用户名: admin
                       密  码: {}
                     这个密码只存在于内存，重启后会变。
                     生产环境请在 harken.web.users 里显式配置账号。
                    ============================================================""", generated);
        } else if (map.isEmpty()) {
            log.error("Harken: 没有可用账号，且 generate-password-if-no-users=false。"
                    + "人类控制台将无法登录（fail-closed）。");
        }
        return Map.copyOf(map);
    }

    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(GENERATED_LENGTH);
        for (int i = 0; i < GENERATED_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private void announceGeneratedPassword() {
        if (credentials.isEmpty()) {
            log.warn("Harken: 人类通道当前没有任何可登录账号。");
        }
    }

    private static String resolveAiToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            return SecretResolver.resolve(raw).trim();
        } catch (RuntimeException e) {
            // AI Token 解析失败 → AI 通道直接不启用。绝不能退回「空 Token 也放行」。
            log.error("Harken: AI Token 配置无法解析，AI 通道已禁用。原因：{}", e.getMessage());
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 登录 / 登出
    // ------------------------------------------------------------------

    /**
     * 登录结果状态。
     *
     * @author harlan
     * @since 1.0.0
     */
    public enum LoginStatus {
        /** 成功。 */
        OK,
        /** 用户名或密码错误。两种原因不区分。 */
        BAD_CREDENTIALS,
        /** 失败次数过多，暂时锁定。 */
        LOCKED,
        /** 系统没有任何可用账号。 */
        NO_ACCOUNTS
    }

    /**
     * 登录结果。
     *
     * @param status             状态
     * @param session            成功时的会话，失败为 {@code null}
     * @param retryAfterSeconds  锁定时建议等待的秒数，其他情况为 0
     */
    public record LoginResult(LoginStatus status, SessionStore.Session session, long retryAfterSeconds) {

        /**
         * @return 是否成功
         */
        public boolean ok() {
            return status == LoginStatus.OK;
        }
    }

    /**
     * 校验用户名密码并建立会话。
     *
     * @param username 用户名，可为 {@code null}
     * @param password 明文密码，可为 {@code null}
     * @return 登录结果，永不返回 {@code null}
     */
    public LoginResult login(String username, String password) {
        if (username == null || username.isBlank()) {
            // 空用户名也要走一遍哈希，避免从耗时区分「参数没填」和「用户不存在」
            PasswordHash.verify("x", DUMMY_HASH);
            return new LoginResult(LoginStatus.BAD_CREDENTIALS, null, 0);
        }
        String key = username.trim();
        if (throttle.isLocked(key)) {
            return new LoginResult(LoginStatus.LOCKED, null, throttle.lockedSecondsRemaining(key));
        }
        Map<String, Credential> snapshot = credentials;
        if (snapshot.isEmpty()) {
            PasswordHash.verify("x", DUMMY_HASH);
            return new LoginResult(LoginStatus.NO_ACCOUNTS, null, 0);
        }
        Credential credential = snapshot.get(key);
        boolean matched = PasswordHash.verify(password == null ? "" : password,
                credential == null ? DUMMY_HASH : credential.secret());
        if (credential == null || !matched) {
            int failures = throttle.recordFailure(key);
            log.warn("Harken: 用户 {} 登录失败（连续第 {} 次）", key, failures);
            if (throttle.isLocked(key)) {
                log.warn("Harken: 用户 {} 连续失败 {} 次，已锁定 {} 分钟",
                        key, failures, config.lockDuration().toMinutes());
                return new LoginResult(LoginStatus.LOCKED, null, throttle.lockedSecondsRemaining(key));
            }
            return new LoginResult(LoginStatus.BAD_CREDENTIALS, null, 0);
        }
        throttle.recordSuccess(key);
        SessionStore.Session session = sessions.create(credential.username(), credential.role());
        log.info("Harken: 用户 {} 登录成功，角色 {}", credential.username(), credential.role());
        return new LoginResult(LoginStatus.OK, session, 0);
    }

    /**
     * 登出。
     *
     * @param token 会话令牌
     * @return 是否确实删掉了一个会话
     */
    public boolean logout(String token) {
        return sessions.invalidate(token);
    }

    /**
     * 校验并续期会话。
     *
     * @param token 令牌
     * @return 会话，无效时为 {@code null}
     */
    public SessionStore.Session touch(String token) {
        return sessions.touch(token);
    }

    /**
     * 只读校验，不续期。
     *
     * @param token 令牌
     * @return 会话，无效时为 {@code null}
     */
    public SessionStore.Session peek(String token) {
        return sessions.peek(token);
    }

    // ------------------------------------------------------------------
    // 重载
    // ------------------------------------------------------------------

    /**
     * 重载结果。
     *
     * @param userCount          重载后可用账号数
     * @param droppedSessions    被踢掉的会话数
     * @param disabledUsers      解析失败而被禁用的用户名
     */
    public record ReloadResult(int userCount, int droppedSessions, List<String> disabledUsers) {
    }

    /**
     * 重新解析 {@code env:} 引用并清空所有会话。
     *
     * @return 重载摘要
     */
    public synchronized ReloadResult reload() {
        Map<String, Credential> fresh = loadCredentials(userSpecs);
        List<String> disabled = userSpecs.stream()
                .map(HarkenWebConfig.UserSpec::username)
                .filter(name -> !fresh.containsKey(name))
                .toList();
        this.credentials = fresh;
        this.aiToken = resolveAiToken(config.aiToken());
        // 密钥轮换后旧签名必须立即失效，所以校验器一起重建（旧的 nonce 表也随之丢弃 ——
        // 换密钥本身就意味着「之前那批请求作废」，重建是符合语义的）
        this.signatureVerifier = buildVerifier(config);
        int dropped = sessions.invalidateAll();
        log.info("Harken: 凭据已重载，可用账号 {} 个，踢掉会话 {} 个", fresh.size(), dropped);
        return new ReloadResult(fresh.size(), dropped, disabled);
    }

    // ------------------------------------------------------------------
    // AI Token
    // ------------------------------------------------------------------

    /**
     * AI 通道是否启用。
     *
     * @return 配置了 Token 才返回 {@code true}
     */
    public boolean aiEnabled() {
        return !aiToken.isEmpty();
    }

    /**
     * 校验 AI Token。
     *
     * <p>用 {@link java.security.MessageDigest#isEqual} 做常量时间比较：
     * 普通的 {@code equals} 会在第一个不同的字节提前返回，
     * 网络抖动虽然淹没这个差异，但没有理由省这一行。
     *
     * @param presented 请求带来的 Token
     * @return 是否匹配
     */
    public boolean checkAiToken(String presented) {
        String expected = aiToken;
        if (expected.isEmpty() || presented == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * @return 有效会话数（健康检查用）
     */
    public int activeSessionCount() {
        return sessions.size();
    }

    /**
     * @return 当前可用账号数
     */
    public int userCount() {
        return credentials.size();
    }

    /**
     * 当前时间戳，供测试注入用（包级可见，避免公开 API 出现奇怪的方法）。
     *
     * @return 当前时刻
     */
    Instant now() {
        return Instant.now();
    }
}
