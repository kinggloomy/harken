package com.harlanking.demo;

import com.harlanking.harken.core.HarkenOptions;
import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.api.ReadOnlyRequest;
import com.harlanking.harken.core.api.ReadWriteRequest;
import com.harlanking.harken.core.api.ScriptResult;
import com.harlanking.harken.core.engine.GroovyScriptEngine;
import com.harlanking.harken.core.impl.DefaultScriptValidator;
import com.harlanking.harken.core.impl.MapBeanResolver;
import com.harlanking.harken.core.spi.AuditRecord;
import com.harlanking.harken.jdbc.GuardPolicy;
import com.harlanking.harken.jdbc.GuardedDataSource;
import com.harlanking.harken.jdbc.JdbcAuditStore;
import com.harlanking.harken.jdbc.JdbcWriteGuard;
import com.harlanking.harken.spring.SpringTransactionBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在真实 PostgreSQL 上验证数据保护三件套。
 *
 * <h2>为什么 H2 的绿色不够</h2>
 * <p>M1 已经吃过一次教训：H2 忽略 {@code Connection.setReadOnly(true)}，
 * 于是「试跑」在 H2 上假绿、在 MySQL/PG 上直接报错。同类的差异在守卫这里还有三处：
 * <ul>
 *   <li>{@code create table if not exists ... boolean ... timestamp} 是否被接受（审计建表）</li>
 *   <li>{@code order by ... limit n} 的语法与类型</li>
 *   <li>异常从 JDBC 驱动穿过 Spring 事务模板时，事务是否真的回滚</li>
 * </ul>
 * 这三条只有真的连一个严格数据库才能确认。
 *
 * <p>默认跳过（没有 {@code HARKEN_PG_URL} 时），普通 {@code mvn test} 不受影响。要跑：
 * <pre>{@code
 * HARKEN_PG_URL=jdbc:postgresql://127.0.0.1:5432/harken_check \
 * HARKEN_PG_USER=postgres HARKEN_PG_PASSWORD=postgres \
 * mvn -pl harken-demo test -Dtest=PostgresGuardTest
 * }</pre>
 *
 * @author harlan
 * @since 1.0.0
 */
@EnabledIfEnvironmentVariable(named = "HARKEN_PG_URL", matches = ".+")
class PostgresGuardTest {

    private static final String AUDIT_TABLE = "harken_audit_guard_check";

    private DriverManagerDataSource raw;
    private JdbcWriteGuard guard;
    private JdbcAuditStore auditStore;
    private JdbcTemplate jdbc;
    private HarkenService harken;

    @BeforeEach
    void setUp() {
        raw = new DriverManagerDataSource(
                System.getenv("HARKEN_PG_URL"),
                System.getenv().getOrDefault("HARKEN_PG_USER", "postgres"),
                System.getenv().getOrDefault("HARKEN_PG_PASSWORD", "postgres"));

        // 上限 2 行；supply_order 是「关键表」，只许读不许写
        guard = new JdbcWriteGuard(new GuardPolicy(2, List.of("supply_order"), false));
        DataSource guarded = GuardedDataSource.wrap(raw, guard);
        jdbc = new JdbcTemplate(guarded);

        jdbc.execute("create table if not exists demo_order ("
                + "id bigserial primary key, order_no varchar(32) not null unique, status varchar(16) not null)");
        jdbc.execute("create table if not exists supply_order ("
                + "id bigserial primary key, order_no varchar(32) not null unique, status varchar(16) not null)");
        jdbc.execute("drop table if exists " + AUDIT_TABLE);
        jdbc.update("delete from demo_order where order_no like 'GUARD-%'");
        jdbc.update("delete from supply_order where order_no like 'GUARD-%'");
        for (int i = 1; i <= 3; i++) {
            jdbc.update("insert into demo_order (order_no, status) values (?, 'NEW')", "GUARD-" + i);
        }
        jdbc.update("insert into supply_order (order_no, status) values ('GUARD-9', 'OPEN')");

        auditStore = new JdbcAuditStore(raw, AUDIT_TABLE, true);

        Map<String, Object> beans = new LinkedHashMap<>();
        beans.put("orderDao", new OrderDao(jdbc));
        harken = new HarkenService(
                new GroovyScriptEngine(PostgresGuardTest.class.getClassLoader(), 64),
                new MapBeanResolver(beans),
                List.of(),
                List.of(new DefaultScriptValidator()),
                List.of(auditStore),
                new SpringTransactionBridge(new JdbcTransactionManager(guarded)),
                HarkenOptions.defaults(),
                guard);
    }

    @Test
    @DisplayName("PG：行数超限被拒，事务真的回滚，审计记下「本来会改 3 行」")
    void rowLimitRejectsAndRollsBack() {
        ScriptResult r = harken.execute(ReadWriteRequest.dryRun("orderDao.markGuardRowsPaid()"),
                "tester", "127.0.0.1");

        assertFalse(r.ok(), "3 行超过了上限 2 行");
        assertEquals("HARKEN_ROW_LIMIT", r.errorType());
        assertTrue(r.rolledBack());
        assertEquals(3, countGuardRows("NEW"), "被拒之后 3 行必须仍是 NEW");

        AuditRecord record = findAudit("HARKEN_ROW_LIMIT");
        assertEquals(3L, record.affectedRows(), "审计要记下「本来会改多少行」，这才是复盘的关键信息");
        assertTrue(record.rolledBack());
    }

    @Test
    @DisplayName("PG：关键表写被拒、读放行")
    void criticalTableIsProtected() {
        ScriptResult write = harken.execute(ReadWriteRequest.dryRun("orderDao.closeSupplyOrders()"),
                "tester", "127.0.0.1");

        assertFalse(write.ok());
        assertEquals("HARKEN_TABLE_FORBIDDEN", write.errorType());
        assertEquals(1, countSupply("OPEN"), "写入被拦，行状态不该变");

        ScriptResult read = harken.execute(new ReadOnlyRequest("orderDao.openSupplyOrders()"),
                "ai-agent", "127.0.0.1");
        assertTrue(read.ok(), read.error());
        assertEquals("1", read.result(), "关键表必须可读，否则排查都做不了");
    }

    @Test
    @DisplayName("PG：审计表能在 PG 上自动建出来，写入后可查回（含影响行数）")
    void auditStoreWorksOnPostgres() {
        auditStore.save(AuditRecord.builder()
                .traceId("pg-trace-1")
                .at(Instant.now())
                .channel("human")
                .operator("harlan")
                .commitRequested(true)
                .rolledBack(true)
                .ok(false)
                .errorType("HARKEN_ROW_LIMIT")
                .affectedRows(200)
                .durationMs(12)
                .build());

        List<AuditRecord> rows = auditStore.query(5);

        assertEquals(1, rows.size());
        AuditRecord r = rows.get(0);
        assertEquals("pg-trace-1", r.traceId());
        assertEquals("human", r.channel());
        assertEquals("HARKEN_ROW_LIMIT", r.errorType());
        assertEquals(200L, r.affectedRows());
        assertFalse(r.ok());
        assertTrue(r.rolledBack());
        assertNotNull(r.at());
    }

    // ------------------------------------------------------------------

    private AuditRecord findAudit(String errorType) {
        return auditStore.query(50).stream()
                .filter(x -> errorType.equals(x.errorType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("审计表里没有 " + errorType + " 的记录"));
    }

    private int countGuardRows(String status) {
        Integer n = jdbc.queryForObject(
                "select count(*) from demo_order where order_no like 'GUARD-%' and status = ?",
                Integer.class, status);
        return n == null ? -1 : n;
    }

    private int countSupply(String status) {
        Integer n = jdbc.queryForObject(
                "select count(*) from supply_order where order_no like 'GUARD-%' and status = ?",
                Integer.class, status);
        return n == null ? -1 : n;
    }

    /** 被测业务 Bean：普通 JdbcTemplate 调用，不含任何 Harken API。 */
    public static class OrderDao {

        private final JdbcTemplate jdbc;

        public OrderDao(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        /** 一次改 3 行，超过上限 2 行。 */
        public int markGuardRowsPaid() {
            return jdbc.update("update demo_order set status = 'PAID' where order_no like 'GUARD-%'");
        }

        public int closeSupplyOrders() {
            return jdbc.update("update supply_order set status = 'CLOSED' where order_no like 'GUARD-%'");
        }

        public long openSupplyOrders() {
            Long n = jdbc.queryForObject(
                    "select count(*) from supply_order where order_no like 'GUARD-%' and status = 'OPEN'", Long.class);
            return n == null ? 0L : n;
        }
    }
}
