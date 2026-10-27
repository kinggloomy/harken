package com.harlanking.harken.core.impl;

import com.harlanking.harken.core.spi.AuditRecord;
import com.harlanking.harken.core.spi.AuditStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认审计实现：写日志，零建表。
 *
 * <p>为什么这是默认：大部分用户装这个工具只想"有个地方能看到上次谁跑了什么"，
 * 而日志系统（ELK / Loki / SLS）运维本来就在收。写 {@code log.error} 和写
 * {@code INSERT} 的可见性差不多，但前者不占业务库、不需要 DDL、不担心磁盘写满。
 *
 * <p>输出格式是一行一条 JSON，方便日志系统解析。用户只需给 {@code HARKEN_AUDIT}
 * 这个 logger 单独配一个 appender。
 *
 * <p>成功走 {@code INFO}，失败走 {@code WARN} —— 分开级别是为了让运维能只对失败告警，
 * 而不必在几百条正常记录里做文本匹配。
 *
 * <p>本类无状态、线程安全，可直接复用单例。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class Slf4jAuditStore implements AuditStore {

    /** 审计专用 logger 名。用户应给这个名字单独配 appender 与保留策略。 */
    public static final String LOGGER_NAME = "HARKEN_AUDIT";

    private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER_NAME);

    /**
     * 写一条审计。成功 {@code INFO}，失败 {@code WARN}。
     *
     * <p>实现内部不吞异常：日志框架自身的故障（磁盘满、appender 报错）会向上抛，
     * 由 {@code HarkenService} 统一兜住并打 ERROR —— 那里才是「不能影响主流程」的边界。
     *
     * @param r 审计记录
     */
    @Override
    public void save(AuditRecord r) {
        // 失败和成功的级别分开，方便运维只对失败告警
        if (r.ok()) {
            AUDIT.info(toJson(r));
        } else {
            AUDIT.warn(toJson(r));
        }
    }

    /**
     * 转成一行 JSON。包级可见是为了让测试能直接断言格式，
     * 又不用为了抓日志去装一个 appender。
     *
     * @param r 审计记录
     * @return 单行 JSON
     */
    static String toJson(AuditRecord r) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "trace", r.traceId()).append(',');
        field(sb, "at", r.at() == null ? null : r.at().toString()).append(',');
        field(sb, "channel", r.channel()).append(',');
        field(sb, "operator", r.operator()).append(',');
        field(sb, "ip", r.clientIp()).append(',');
        field(sb, "commit", String.valueOf(r.commitRequested())).append(',');
        field(sb, "rolledBack", String.valueOf(r.rolledBack())).append(',');
        field(sb, "ok", String.valueOf(r.ok())).append(',');
        field(sb, "errType", r.errorType()).append(',');
        field(sb, "err", r.errorMsg()).append(',');
        field(sb, "result", r.resultDigest()).append(',');
        field(sb, "ms", String.valueOf(r.durationMs())).append(',');
        // -1 表示没装写入守卫，不是「0 行」——两者在事后复盘时含义完全不同
        field(sb, "rows", r.affectedRows() < 0 ? null : String.valueOf(r.affectedRows())).append(',');
        field(sb, "digest", r.scriptDigest()).append(',');
        field(sb, "script", r.script());
        sb.append('}');
        return sb.toString();
    }

    private static StringBuilder field(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(value)).append('"');
        }
        return sb;
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
