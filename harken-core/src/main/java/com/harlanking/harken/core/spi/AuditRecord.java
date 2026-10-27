package com.harlanking.harken.core.spi;

import java.time.Instant;

/**
 * 一条审计记录。
 *
 * <p><b>不存完整返回值。</b> 脚本 {@code return} 一个十万条记录的 List，序列化后几十 MB，
 * 写进审计表会直接撑爆存储，也会把日志系统拖死。只存摘要 {@code resultDigest}
 * （形如 {@code sha256:ab12cd34 len=12000}），需要完整结果时由脚本自己落文件。
 *
 * <p><b>{@code commitRequested} 与 {@code rolledBack} 是两个独立字段。</b>
 * 用户请求了提交但因异常仍然回滚，这两个值不同 —— 这个差异本身就是重要审计信息：
 * 「想改没改成」和「没想改」是完全不同的两件事。
 *
 * @param traceId         本次执行的短 ID（16 位十六进制），用于串起日志
 * @param at              执行完成时间
 * @param channel         {@code ai} 或 {@code human}
 * @param operator        操作人，可能为 {@code null}（未认证场景）
 * @param clientIp        客户端 IP，反向代理场景下应取被信任的 {@code X-Real-IP}
 * @param scriptDigest    脚本全文的 sha256 短摘要，用于比对「同一段脚本被跑了多少次」
 * @param script          脚本原文（已按上限截断、可脱敏）
 * @param commitRequested 调用方是否请求提交
 * @param rolledBack      实际是否回滚
 * @param ok              是否执行成功（不代表已提交）
 * @param errorType       错误类型标识，成功为 {@code null}
 * @param errorMsg        错误描述（已截断），成功为 {@code null}
 * @param resultDigest    返回值摘要，未 return 为 {@code null}
 * @param durationMs      耗时毫秒
 * @param affectedRows    本次执行累计影响的行数；{@code -1} 表示没测量（没装守卫）。
 *                        注意这个值在<b>回滚的执行上同样有意义</b>：
 *                        「本来会改 200 行，被行数闸拦下并回滚」是最重要的一类审计
 * @author harlan
 * @since 1.0.0
 */
public record AuditRecord(
        String traceId,
        Instant at,
        String channel,
        String operator,
        String clientIp,
        String scriptDigest,
        String script,
        boolean commitRequested,
        boolean rolledBack,
        boolean ok,
        String errorType,
        String errorMsg,
        String resultDigest,
        long durationMs,
        long affectedRows
) {

    /**
     * 创建一个构建器。默认值：{@code at=now}、{@code channel=human}、
     * {@code rolledBack=true}、{@code ok=true}。
     *
     * @return 新的构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /** {@link AuditRecord} 的构建器，仅用于装配，不做校验。 */
    public static final class Builder {
        private String traceId;
        private Instant at = Instant.now();
        private String channel = "human";
        private String operator;
        private String clientIp;
        private String scriptDigest;
        private String script;
        private boolean commitRequested;
        private boolean rolledBack = true;
        private boolean ok = true;
        private String errorType;
        private String errorMsg;
        private String resultDigest;
        private long durationMs;
        private long affectedRows = -1L;

        /** @param v 追踪 ID @return this */
        public Builder traceId(String v) { this.traceId = v; return this; }
        /** @param v 完成时间 @return this */
        public Builder at(Instant v) { this.at = v; return this; }
        /** @param v {@code ai} 或 {@code human} @return this */
        public Builder channel(String v) { this.channel = v; return this; }
        /** @param v 操作人 @return this */
        public Builder operator(String v) { this.operator = v; return this; }
        /** @param v 客户端 IP @return this */
        public Builder clientIp(String v) { this.clientIp = v; return this; }
        /** @param v 脚本摘要 @return this */
        public Builder scriptDigest(String v) { this.scriptDigest = v; return this; }
        /** @param v 脚本原文 @return this */
        public Builder script(String v) { this.script = v; return this; }
        /** @param v 是否请求提交 @return this */
        public Builder commitRequested(boolean v) { this.commitRequested = v; return this; }
        /** @param v 是否已回滚 @return this */
        public Builder rolledBack(boolean v) { this.rolledBack = v; return this; }
        /** @param v 是否成功 @return this */
        public Builder ok(boolean v) { this.ok = v; return this; }
        /** @param v 错误类型 @return this */
        public Builder errorType(String v) { this.errorType = v; return this; }
        /** @param v 错误描述 @return this */
        public Builder errorMsg(String v) { this.errorMsg = v; return this; }
        /** @param v 返回值摘要 @return this */
        public Builder resultDigest(String v) { this.resultDigest = v; return this; }
        /** @param v 耗时毫秒 @return this */
        public Builder durationMs(long v) { this.durationMs = v; return this; }
        /** @param v 影响行数，{@code -1} 表示未测量 @return this */
        public Builder affectedRows(long v) { this.affectedRows = v; return this; }

        /**
         * 装配成不可变记录。
         *
         * @return 审计记录
         */
        public AuditRecord build() {
            return new AuditRecord(traceId, at, channel, operator, clientIp, scriptDigest,
                    script, commitRequested, rolledBack, ok, errorType, errorMsg,
                    resultDigest, durationMs, affectedRows);
        }
    }
}
