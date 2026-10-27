package com.harlanking.harken.jdbc;

import com.harlanking.harken.core.HarkenException;
import com.harlanking.harken.core.spi.WriteGuard;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫在真实数据库（H2 内存库）上的行为。
 *
 * <p>为什么不用 fake {@code DataSource}：这个类最容易出错的不是逻辑，而是
 * <b>代理与驱动的交互</b>（返回类型、异常外壳、{@code getUpdateCount}）。
 * 只有真的连一个数据库才能证明它不改变 JDBC 语义。
 */
class JdbcWriteGuardTest {

    private JdbcDataSource raw;

    @BeforeEach
    void setUp() throws Exception {
        raw = new JdbcDataSource();
        raw.setURL("jdbc:h2:mem:guard_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        raw.setUser("sa");
        try (Connection c = raw.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table demo_order(id bigint primary key, status varchar(16) not null)");
            for (int i = 1; i <= 5; i++) {
                s.execute("insert into demo_order(id, status) values (" + i + ", 'NEW')");
            }
            s.execute("create table supply_order(id bigint primary key, status varchar(16))");
        }
    }

    @Test
    @DisplayName("UPDATE 的影响行数被累计，并能在作用域关闭前读到")
    void countsAffectedRows() throws Exception {
        JdbcWriteGuard guard = new JdbcWriteGuard(GuardPolicy.passThrough());
        DataSource ds = GuardedDataSource.wrap(raw, guard);

        try (WriteGuard.Scope scope = guard.begin("t1");
             Connection c = ds.getConnection();
             Statement s = c.createStatement()) {

            int n = s.executeUpdate("update demo_order set status = 'PAID' where id <= 3");

            assertEquals(3, n);
            assertEquals(3, scope.affectedRows());
        }
    }

    @Test
    @DisplayName("PreparedStatement 用 execute() 时也能数到（靠 getUpdateCount 补问一句）")
    void countsPreparedStatementExecute() throws Exception {
        JdbcWriteGuard guard = new JdbcWriteGuard(GuardPolicy.passThrough());
        DataSource ds = GuardedDataSource.wrap(raw, guard);

        try (WriteGuard.Scope scope = guard.begin("t2");
             Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "update demo_order set status = 'PAID' where id <= 4")) {

            boolean hasResultSet = ps.execute();

            assertEquals(false, hasResultSet);
            assertEquals(4, scope.affectedRows());
        }
    }

    @Test
    @DisplayName("批量执行累加所有子语句的行数")
    void countsBatch() throws Exception {
        JdbcWriteGuard guard = new JdbcWriteGuard(GuardPolicy.passThrough());
        DataSource ds = GuardedDataSource.wrap(raw, guard);

        try (WriteGuard.Scope scope = guard.begin("t3");
             Connection c = ds.getConnection();
             Statement s = c.createStatement()) {

            s.addBatch("update demo_order set status = 'A' where id = 1");
            s.addBatch("update demo_order set status = 'B' where id = 2");
            s.executeBatch();

            assertEquals(2, scope.affectedRows());
        }
    }

    @Test
    @DisplayName("超过行数上限立刻抛 HARKEN_ROW_LIMIT，而不是等到提交")
    void rejectsWhenRowLimitExceeded() throws Exception {
        JdbcWriteGuard guard = new JdbcWriteGuard(new GuardPolicy(2, List.of(), false));
        DataSource ds = GuardedDataSource.wrap(raw, guard);

        try (WriteGuard.Scope scope = guard.begin("t4");
             Connection c = ds.getConnection();
             Statement s = c.createStatement()) {

            HarkenException.Rejected e = assertThrows(HarkenException.Rejected.class,
                    () -> s.executeUpdate("update demo_order set status = 'PAID'"));

            assertEquals(JdbcWriteGuard.CODE_ROW_LIMIT, e.code());
            assertTrue(e.getMessage().contains("上限 2"), e.getMessage());
            // 异常里必须带上事实：已经累计了几行
            assertEquals(5, scope.affectedRows());
        }
    }

    @Test
    @DisplayName("行数上限是累计的：第二条语句把总数顶过线也会被拒")
    void rowLimitIsCumulativeAcrossStatements() throws Exception {
        JdbcWriteGuard guard = new JdbcWriteGuard(new GuardPolicy(3, List.of(), false));
        DataSource ds = GuardedDataSource.wrap(raw, guard);

        try (WriteGuard.Scope scope = guard.begin("t5");
             Connection c = ds.getConnection();
             Statement s = c.createStatement()) {

            s.executeUpdate("update demo_order set status = 'A' where id <= 2");
            HarkenException.Rejected e = assertThrows(HarkenException.Rejected.class,
                    () -> s.executeUpdate("update demo_order set status = 'B' where id <= 2"));

            assertEquals(JdbcWriteGuard.CODE_ROW_LIMIT, e.code());
            assertEquals(4, scope.affectedRows());
        }
    }

    @Test
    @DisplayName("行数超限后回滚，数据真的没变（这不只是抛了个异常）")
    void rollbackAfterRejectionKeepsData() throws Exception {
        JdbcWriteGuard guard = new JdbcWriteGuard(new GuardPolicy(2, List.of(), false));
        DataSource ds = GuardedDataSource.wrap(raw, guard);

        try (WriteGuard.Scope scope = guard.begin("t6");
             Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) {
                    assertThrows(HarkenException.Rejected.class,
                            () -> s.executeUpdate("update demo_order set status = 'PAID'"));
                }
            } finally {
                c.rollback();
            }
        }

        assertEquals(0, countPaid());
    }

    @Test
    @DisplayName("黑名单表：写被拒，且真的没有执行（表里还是空的）")
    void rejectsWriteOnDeniedTable() throws Exception {
        JdbcWriteGuard guard = new JdbcWriteGuard(new GuardPolicy(0, List.of("supply_order"), false));
        DataSource ds = GuardedDataSource.wrap(raw, guard);

        try (WriteGuard.Scope scope = guard.begin("t7");
             Connection c = ds.getConnection();
             Statement s = c.createStatement()) {

            HarkenException.Rejected e = assertThrows(HarkenException.Rejected.class,
                    () -> s.executeUpdate("insert into supply_order(id, status) values (1, 'NEW')"));

            assertEquals(JdbcWriteGuard.CODE_TABLE_FORBIDDEN, e.code());
            assertTrue(e.getMessage().contains("supply_order"), e.getMessage());
        }

        assertEquals(0, countAll("supply_order"));
    }

    @Test
    @DisplayName("黑名单只管写，读关键表必须放行 —— 否则排查问题都做不了")
    void allowsReadingDeniedTable() throws Exception {
        JdbcWriteGuard guard = new JdbcWriteGuard(new GuardPolicy(0, List.of("supply_order"), false));
        DataSource ds = GuardedDataSource.wrap(raw, guard);

        try (WriteGuard.Scope scope = guard.begin("t8");
             Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("select count(*) from supply_order");
             ResultSet rs = ps.executeQuery()) {

            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
            assertEquals(0, scope.affectedRows(), "读操作不该计入影响行数");
        }
    }

    @Test
    @DisplayName("策略禁止 DDL 时，create table 在提交给数据库之前就被拦下")
    void rejectsDdlWhenConfigured() throws Exception {
        JdbcWriteGuard guard = new JdbcWriteGuard(new GuardPolicy(0, List.of(), true));
        DataSource ds = GuardedDataSource.wrap(raw, guard);

        try (WriteGuard.Scope scope = guard.begin("t9");
             Connection c = ds.getConnection();
             Statement s = c.createStatement()) {

            HarkenException.Rejected e = assertThrows(HarkenException.Rejected.class,
                    () -> s.execute("create table tmp_x(a int)"));

            assertEquals(JdbcWriteGuard.CODE_DDL_FORBIDDEN, e.code());
        }
    }

    @Test
    @DisplayName("没开作用域时完全直通：应用自己的 SQL 不受任何影响")
    void passThroughOutsideScope() throws Exception {
        JdbcWriteGuard guard = new JdbcWriteGuard(new GuardPolicy(1, List.of("supply_order"), true));
        DataSource ds = GuardedDataSource.wrap(raw, guard);

        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            // 行数上限 1 + 黑名单 + 禁 DDL，作用域外全部不生效
            s.executeUpdate("insert into supply_order(id, status) values (1, 'NEW')");
            s.executeUpdate("insert into supply_order(id, status) values (2, 'NEW')");
            s.execute("create table tmp_y(a int)");
        }

        assertEquals(2, countAll("supply_order"));
    }

    @Test
    @DisplayName("嵌套作用域各自独立，内层关闭后外层继续生效")
    void nestedScopes() throws Exception {
        JdbcWriteGuard guard = new JdbcWriteGuard(new GuardPolicy(0, List.of("supply_order"), false));
        DataSource ds = GuardedDataSource.wrap(raw, guard);

        try (WriteGuard.Scope outer = guard.begin("outer");
             Connection c = ds.getConnection()) {

            try (WriteGuard.Scope inner = guard.begin("inner");
                 Statement s = c.createStatement()) {
                s.executeUpdate("update demo_order set status = 'INNER' where id = 1");
                assertEquals(1, inner.affectedRows());
            }

            try (Statement s = c.createStatement()) {
                s.executeUpdate("update demo_order set status = 'OUTER' where id = 2");
            }

            // 外层不应该包含内层的那 1 行
            assertEquals(1, outer.affectedRows());
        }
    }

    @Test
    @DisplayName("包装是幂等的，重复 wrap 不会让行数翻倍")
    void wrapIsIdempotent() {
        JdbcWriteGuard guard = new JdbcWriteGuard(GuardPolicy.passThrough());
        DataSource once = GuardedDataSource.wrap(raw, guard);
        DataSource twice = GuardedDataSource.wrap(once, guard);

        assertTrue(GuardedDataSource.isGuarded(once));
        assertEquals(once, twice);
        assertEquals(raw, GuardedDataSource.unwrap(once));
    }

    // ------------------------------------------------------------------

    private long countPaid() throws Exception {
        try (Connection c = raw.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "select count(*) from demo_order where status = 'PAID'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countAll(String table) throws Exception {
        try (Connection c = raw.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
