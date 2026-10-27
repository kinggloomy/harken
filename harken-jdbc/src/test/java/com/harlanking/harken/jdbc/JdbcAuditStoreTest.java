package com.harlanking.harken.jdbc;

import com.harlanking.harken.core.spi.AuditRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcAuditStore} 的行为验证。
 *
 * <p>最要紧的一条是 {@link #auditSurvivesRollback()}：审计必须写在业务事务之外，
 * 否则「被行数闸拦下并回滚」的那次执行不会留下任何记录，而那正是最需要查的一次。
 */
class JdbcAuditStoreTest {

    private JdbcDataSource raw;

    @BeforeEach
    void setUp() throws Exception {
        raw = new JdbcDataSource();
        raw.setURL("jdbc:h2:mem:audit_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        raw.setUser("sa");
    }

    @Test
    @DisplayName("写入后能查回来，字段一个不少（含影响行数）")
    void saveAndQueryRoundTrip() {
        JdbcAuditStore store = new JdbcAuditStore(raw, "harken_audit", true);
        AuditRecord record = sample();

        store.save(record);
        List<AuditRecord> rows = store.query(10);

        assertEquals(1, rows.size());
        AuditRecord r = rows.get(0);
        assertEquals("trace-1", r.traceId());
        assertEquals("human", r.channel());
        assertEquals("harlan", r.operator());
        assertEquals("10.0.1.5", r.clientIp());
        assertEquals("sha256:abc123", r.scriptDigest());
        assertEquals("update demo_order set status='PAID'", r.script());
        assertTrue(r.commitRequested());
        assertFalse(r.rolledBack());
        assertTrue(r.ok());
        assertEquals("HARKEN_ROW_LIMIT", r.errorType());
        assertEquals("超过上限", r.errorMsg());
        assertEquals("sha256:def456 len=3", r.resultDigest());
        assertEquals(123L, r.durationMs());
        assertEquals(200L, r.affectedRows());
        assertEquals(record.at().toEpochMilli(), r.at().toEpochMilli());
    }

    @Test
    @DisplayName("autoCreate 会建表；关掉之后表不存在就报错（而不是静默丢记录）")
    void autoCreateSwitch() {
        JdbcAuditStore noCreate = new JdbcAuditStore(raw, "audit_manual", false);
        assertThrows(IllegalStateException.class, () -> noCreate.save(sample()));

        JdbcAuditStore create = new JdbcAuditStore(raw, "audit_auto", true);
        create.save(sample());
        assertEquals(1, create.query(5).size());
    }

    @Test
    @DisplayName("审计写在独立连接上：业务事务回滚了，审计仍然在")
    void auditSurvivesRollback() throws Exception {
        JdbcAuditStore store = new JdbcAuditStore(raw, "harken_audit", true);
        store.save(sample());   // 先把表建出来

        // 模拟一次「脚本写库后回滚」的业务事务
        try (Connection c = raw.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("create table if not exists rollback_probe(id bigint)");
                s.execute("insert into rollback_probe(id) values (1)");
            }
            c.rollback();
        }

        AuditRecord second = AuditRecord.builder()
                .traceId("trace-after-rollback")
                .at(Instant.now())
                .channel("human")
                .operator("harlan")
                .ok(false)
                .errorType("HARKEN_ROW_LIMIT")
                .affectedRows(500)
                .build();
        store.save(second);

        List<AuditRecord> rows = store.query(10);
        assertEquals(2, rows.size());
        assertEquals("trace-after-rollback", rows.get(0).traceId(), "最新的排在最前面");
        assertEquals(500L, rows.get(0).affectedRows());

        try (Connection c = raw.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from rollback_probe")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "业务事务确实回滚了");
        }
    }

    @Test
    @DisplayName("传进来的是被守卫包装过的数据源时自动拆开，审计不会被自己的守卫拦下")
    void unwrapsGuardedDataSource() {
        JdbcWriteGuard guard = new JdbcWriteGuard(new GuardPolicy(1, List.of("harken_audit"), false));
        JdbcAuditStore store = new JdbcAuditStore(
                GuardedDataSource.wrap(raw, guard), "harken_audit", true);

        // 若没拆开：harken_audit 在黑名单里，这次插入会被拒绝
        store.save(sample());

        assertEquals(1, store.query(5).size());
    }

    @Test
    @DisplayName("表名不是合法标识符时直接拒绝：这是配置注入的唯一入口")
    void rejectsIllegalTableName() {
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcAuditStore(raw, "harken_audit; drop table x", true));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcAuditStore(raw, "", true));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcAuditStore(null, "harken_audit", true));
    }

    @Test
    @DisplayName("schema 前缀合法；name() 带表名，便于在启动日志里一眼看到落点")
    void supportsSchemaPrefix() {
        JdbcAuditStore store = new JdbcAuditStore(raw, "PUBLIC.harken_audit", true);

        store.save(sample());

        assertEquals("JdbcAuditStore(PUBLIC.harken_audit)", store.name());
        assertTrue(store.supportsQuery());
    }

    @Test
    @DisplayName("可空字段为空时不会写出 \"null\" 字符串")
    void nullFieldsStayNull() {
        JdbcAuditStore store = new JdbcAuditStore(raw, "harken_audit", true);
        store.save(AuditRecord.builder()
                .traceId("t-null")
                .at(Instant.now())
                .channel("ai")
                .build());

        AuditRecord r = store.query(1).get(0);
        assertNull(r.operator());
        assertNull(r.errorType());
        assertNull(r.resultDigest());
        assertEquals(-1L, r.affectedRows(), "未测量的行数必须是 -1，不能是 0");
    }

    private static AuditRecord sample() {
        return AuditRecord.builder()
                .traceId("trace-1")
                .at(Instant.now())
                .channel("human")
                .operator("harlan")
                .clientIp("10.0.1.5")
                .scriptDigest("sha256:abc123")
                .script("update demo_order set status='PAID'")
                .commitRequested(true)
                .rolledBack(false)
                .ok(true)
                .errorType("HARKEN_ROW_LIMIT")
                .errorMsg("超过上限")
                .resultDigest("sha256:def456 len=3")
                .durationMs(123)
                .affectedRows(200)
                .build();
    }
}
