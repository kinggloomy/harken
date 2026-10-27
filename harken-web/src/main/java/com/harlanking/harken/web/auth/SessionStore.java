package com.harlanking.harken.web.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存会话表。
 *
 * <h2>为什么不是 JWT</h2>
 * <p>JWT 的优势是「无状态、多实例共享」，但它换来的代价是：<b>签发出去的令牌无法撤销</b>。
 * 对运维控制台来说，「改完密码后，旧会话必须立刻失效」是硬需求，而 JWT 只能靠
 * 额外的黑名单来实现 —— 那时你已经有一张会话表了，还多了一个签名校验的复杂度。
 *
 * <p>Harken 是单体内嵌工具，会话表就在进程内存里，撤销是 {@code remove} 一次哈希查找。
 * 重启后会话全失效，这恰恰是运维控制台应有的行为。
 *
 * <h2>两个过期时间必须同时存在</h2>
 * <ul>
 *   <li><b>空闲超时</b>（默认 30 分钟）：一段时间不用就失效。防止人离开工位后会话被接手。</li>
 *   <li><b>绝对上限</b>（默认 8 小时）：无论多活跃，到点就得重新登录。
 *       只靠空闲超时的话，一个每天点一下的页面可以让会话活到天荒地老。</li>
 * </ul>
 *
 * <p>本类线程安全。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class SessionStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger opsSinceSweep = new AtomicInteger();
    private final Duration idleTimeout;
    private final Duration absoluteTimeout;

    /**
     * @param idleTimeout     空闲超时，必须为正
     * @param absoluteTimeout 绝对上限，必须为正且不短于空闲超时
     */
    public SessionStore(Duration idleTimeout, Duration absoluteTimeout) {
        this.idleTimeout = requirePositive(idleTimeout, "idleTimeout");
        this.absoluteTimeout = requirePositive(absoluteTimeout, "absoluteTimeout");
    }

    private static Duration requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正");
        }
        return d;
    }

    /**
     * 一条会话。
     *
     * @param token     会话令牌
     * @param username  用户名
     * @param role      角色（登录时快照；重载凭据时会话会被清掉，所以不需要动态查角色）
     * @param createdAt 创建时刻，用于绝对上限
     * @param seenAt    最近访问时刻，用于空闲超时
     */
    public record Session(String token, String username, Role role, Instant createdAt, Instant seenAt) {

        /**
         * @return 剩余有效时间（毫秒），已过期返回 0
         */
        public long remainingMillis(Instant now, Duration idleTimeout, Duration absoluteTimeout) {
            Instant idleDeadline = seenAt.plus(idleTimeout);
            Instant absoluteDeadline = createdAt.plus(absoluteTimeout);
            Instant deadline = idleDeadline.isBefore(absoluteDeadline) ? idleDeadline : absoluteDeadline;
            long ms = Duration.between(now, deadline).toMillis();
            return Math.max(0, ms);
        }
    }

    /**
     * 新建会话。
     *
     * @param username 用户名
     * @param role     角色
     * @return 新会话
     */
    public Session create(String username, Role role) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(role, "role");
        sweepIfNeeded();
        String token = newToken();
        Instant now = Instant.now();
        Session session = new Session(token, username, role, now, now);
        sessions.put(token, session);
        return session;
    }

    private static String newToken() {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * 校验并续期。
     *
     * <p>返回 {@code null} 表示无效：令牌不存在、已空闲超时、已到绝对上限。
     * 三种情况对外都是一个结果，调用方不需要区分 —— 区分只会给攻击者提供信息。
     *
     * @param token 令牌
     * @return 续期后的会话，或 {@code null}
     */
    public Session touch(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Session existing = sessions.get(token);
        if (existing == null) {
            return null;
        }
        Instant now = Instant.now();
        if (isExpired(existing, now)) {
            sessions.remove(token, existing);
            return null;
        }
        Session renewed = new Session(existing.token(), existing.username(), existing.role(),
                existing.createdAt(), now);
        // 用 replace 而不是 put：并发下如果会话刚被 invalidate，不要复活它
        return sessions.replace(token, existing, renewed) ? renewed : null;
    }

    /**
     * 只读校验，不续期。给「只想看看是否还有效」的场景用。
     *
     * @param token 令牌
     * @return 会话，或 {@code null}
     */
    public Session peek(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Session existing = sessions.get(token);
        if (existing == null) {
            return null;
        }
        if (isExpired(existing, Instant.now())) {
            sessions.remove(token, existing);
            return null;
        }
        return existing;
    }

    private boolean isExpired(Session s, Instant now) {
        return now.isAfter(s.seenAt().plus(idleTimeout)) || now.isAfter(s.createdAt().plus(absoluteTimeout));
    }

    /**
     * 使单个会话失效（登出）。
     *
     * @param token 令牌
     * @return 是否确实删掉了一个会话
     */
    public boolean invalidate(String token) {
        return token != null && sessions.remove(token) != null;
    }

    /**
     * 让某个用户的全部会话失效。
     *
     * <p>改密码、重载凭据时必须调用：否则旧会话会带着登录时的旧角色继续用下去，
     * 「降权」就变成了下次登录才生效。
     *
     * @param username 用户名
     * @return 失效的会话数
     */
    public int invalidateUser(String username) {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().username().equals(username));
        return before - sessions.size();
    }

    /**
     * 清空所有会话。重载凭据时用。
     *
     * @return 失效的会话数
     */
    public int invalidateAll() {
        int n = sessions.size();
        sessions.clear();
        return n;
    }

    /**
     * 当前有效会话数（含尚未被清理的过期项，仅供健康检查粗略展示）。
     *
     * @return 会话数
     */
    public int size() {
        return sessions.size();
    }

    private void sweepIfNeeded() {
        // 每 64 次创建清一次，避免「建了不登出」的客户端把内存撑满
        if (opsSinceSweep.incrementAndGet() % 64 != 0) {
            return;
        }
        Instant now = Instant.now();
        sessions.entrySet().removeIf(e -> isExpired(e.getValue(), now));
    }
}
