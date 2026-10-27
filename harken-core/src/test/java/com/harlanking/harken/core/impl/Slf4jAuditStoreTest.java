package com.harlanking.harken.core.impl;

import com.harlanking.harken.core.spi.AuditRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认审计（写日志）的输出格式。日志是给人看、给日志系统解析的，
 * 格式一改，别人的看板就会静默空掉 —— 所以这里锁住几个关键字段。
 */
class Slf4jAuditStoreTest {

    @Test
    @DisplayName("影响行数写入日志；未测量时是 null，不是 0")
    void rowsAreLogged() {
        String measured = Slf4jAuditStore.toJson(AuditRecord.builder()
                .traceId("t1").channel("human").ok(false)
                .errorType("HARKEN_ROW_LIMIT").affectedRows(5).build());
        assertTrue(measured.contains("\"rows\":\"5\""), measured);

        String unknown = Slf4jAuditStore.toJson(AuditRecord.builder().traceId("t2").build());
        assertTrue(unknown.contains("\"rows\":null"), unknown);
        assertFalse(unknown.contains("\"rows\":\"0\""), "没测量不能写成 0 行");
    }

    @Test
    @DisplayName("脚本里的换行与引号被转义，一行一条 JSON 不被撑破")
    void escapesMultilineScript() {
        String json = Slf4jAuditStore.toJson(AuditRecord.builder()
                .traceId("t3")
                .script("def a = 1\nprintln \"x\"")
                .build());

        assertFalse(json.contains("\n"), "JSON 里不能有裸换行");
        assertTrue(json.contains("\\n"), json);
        assertTrue(json.contains("\\\"x\\\""), json);
    }
}
