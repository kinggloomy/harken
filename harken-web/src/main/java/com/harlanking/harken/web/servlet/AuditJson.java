package com.harlanking.harken.web.servlet;

import com.harlanking.harken.core.spi.AuditRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AuditRecord} 的 JSON 形状（给控制台的「最近执行」面板）。
 *
 * <h2>为什么不返回脚本全文</h2>
 * <p>看起来「点一条记录就能把脚本填回编辑器」很顺手，但代价是：
 * 200 条 × 每条最多 4KB ≈ 800KB 的 JSON，每次刷新页面都要传一次。
 * 而<b>回填真正靠的是浏览器本地那份</b>（{@code localStorage}，带完整脚本）。
 *
 * <p>所以这里只给 {@code scriptPreview}（首行，最多 {@value #PREVIEW_CHARS} 字），
 * 让人一眼认出「哦是那次改状态的」。要看全文，去日志或审计表 —— 那才是全量落盘的地方。
 *
 * <h2>字段名与 {@code ScriptResult} 对齐</h2>
 * <p>{@code ok} / {@code rolledBack} / {@code errorType} / {@code affectedRows} 四处同名同义，
 * 前端不必为「历史记录」和「刚刚这次执行」写两套渲染逻辑。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class AuditJson {

    /** 脚本预览的最大字符数。 */
    public static final int PREVIEW_CHARS = 160;

    private AuditJson() {
    }

    /**
     * 转换一批记录。
     *
     * @param records 审计记录，按存储返回的顺序（通常是新到旧）
     * @return 可直接序列化的列表
     */
    public static List<Object> toList(List<AuditRecord> records) {
        List<Object> list = new ArrayList<>(records.size());
        for (AuditRecord record : records) {
            list.add(toMap(record));
        }
        return list;
    }

    /**
     * 转换单条记录。
     *
     * @param record 审计记录
     * @return 可直接序列化的 Map
     */
    public static Map<String, Object> toMap(AuditRecord record) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", record.traceId());
        m.put("at", record.at() == null ? null : record.at().toString());
        m.put("channel", record.channel());
        m.put("operator", record.operator());
        m.put("clientIp", record.clientIp());
        m.put("scriptDigest", record.scriptDigest());
        m.put("scriptPreview", preview(record.script()));
        m.put("commitRequested", record.commitRequested());
        m.put("rolledBack", record.rolledBack());
        m.put("ok", record.ok());
        m.put("errorType", record.errorType());
        m.put("error", record.errorMsg());
        m.put("resultDigest", record.resultDigest());
        m.put("durationMs", record.durationMs());
        m.put("affectedRows", record.affectedRows());
        return m;
    }

    private static String preview(String script) {
        if (script == null || script.isBlank()) {
            return null;
        }
        // 首行非空的行，比「前 160 个字」可读得多：脚本开头往往是 import 或者空行
        for (String line : script.split("\n", 40)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() <= PREVIEW_CHARS
                        ? trimmed
                        : trimmed.substring(0, PREVIEW_CHARS) + "…";
            }
        }
        return null;
    }
}
