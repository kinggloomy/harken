package com.harlanking.demo;

import com.harlanking.harken.core.HarkenOptions;
import com.harlanking.harken.core.HarkenService;
import com.harlanking.harken.core.api.ReadOnlyRequest;
import com.harlanking.harken.core.api.ReadWriteRequest;
import com.harlanking.harken.core.api.ScriptResult;
import com.harlanking.harken.core.engine.GroovyScriptEngine;
import com.harlanking.harken.core.impl.DefaultScriptValidator;
import com.harlanking.harken.core.impl.MapBeanResolver;
import com.harlanking.harken.core.impl.NoopAuditStore;
import com.harlanking.harken.spring.SpringTransactionBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在真实 PostgreSQL 上验证三态事务语义。
 *
 * <h2>为什么单独一个测试，且默认不跑</h2>
 * H2 <b>忽略</b> {@code Connection.setReadOnly(true)}，所以 H2 上的绿色证明不了两件事：
 * <ul>
 *   <li>试跑（{@code ROLLBACK} 模式）能不能真的执行 UPDATE</li>
 *   <li>AI 只读（{@code READ_ONLY} 模式）会不会被数据库真的拒绝</li>
 * </ul>
 * PostgreSQL 会真的发 {@code SET TRANSACTION READ ONLY}。用 H2 测这两条等于没测。
 *
 * <p>默认跳过（没有 {@code HARKEN_PG_URL} 环境变量），所以普通 {@code mvn test}
 * 不需要装数据库。要跑：
 * <pre>{@code
 * HARKEN_PG_URL=jdbc:postgresql://127.0.0.1:5432/harken_check \
 * HARKEN_PG_USER=postgres HARKEN_PG_PASSWORD=postgres \
 * mvn -pl harken-demo test -Dtest=PostgresTransactionTest
 * }</pre>
 *
 * @author harlan
 * @since 1.0.0
 */
@EnabledIfEnvironmentVariable(named = "HARKEN_PG_URL", matches = ".+")
class PostgresTransactionTest {

    private static final String ORDER_NO = "SO-1002";

    private JdbcTemplate jdbc;
    private HarkenService harken;

    @BeforeEach
    void setUp() {
        String url = System.getenv("HARKEN_PG_URL");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                url,
                System.getenv().getOrDefault("HARKEN_PG_USER", "postgres"),
                System.getenv().getOrDefault("HARKEN_PG_PASSWORD", "postgres"));

        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                create table if not exists demo_order (
                    id bigserial primary key,
                    order_no varchar(32) not null unique,
                    status varchar(16) not null
                )
                """);
        jdbc.update("insert into demo_order (order_no, status) values (?, 'NEW') on conflict (order_no) do nothing", ORDER_NO);
        jdbc.update("update demo_order set status = 'NEW' where order_no = ?", ORDER_NO);

        Map<String, Object> beans = new LinkedHashMap<>();
        beans.put("orderDao", new OrderDao(jdbc));

        harken = new HarkenService(
                new GroovyScriptEngine(PostgresTransactionTest.class.getClassLoader(), 64),
                new MapBeanResolver(beans),
                List.of(),
                List.of(new DefaultScriptValidator()),
                List.of(NoopAuditStore.INSTANCE),
                new SpringTransactionBridge(new JdbcTransactionManager(ds)),
                HarkenOptions.defaults());
    }

    @Test
    @DisplayName("PG：试跑能执行 UPDATE 且结束后回滚")
    void dryRunWritesAndRollsBack() {
        ScriptResult r = harken.execute(ReadWriteRequest.dryRun(
                "def n = orderDao.markPaid('" + ORDER_NO + "')\n"
                        + "\"affected=${n}, in-tx=\" + orderDao.statusOf('" + ORDER_NO + "')\n"), "tester", "127.0.0.1");

        assertTrue(r.ok(), "试跑必须能写；旧实现（readOnly=true）这里会抛 cannot execute UPDATE in a read-only transaction：" + r.error());
        assertTrue(r.rolledBack());
        assertTrue(r.result().contains("affected=1"), r.result());
        assertTrue(r.result().contains("in-tx=PAID"), "事务内应能看到自己的改动：" + r.result());

        assertEquals("NEW", statusOf(), "事务外必须看不到改动");
    }

    @Test
    @DisplayName("PG：AI 只读被数据库层拒绝，且不留副作用")
    void aiReadOnlyIsEnforcedByDatabase() {
        ScriptResult r = harken.execute(new ReadOnlyRequest("orderDao.markPaid('" + ORDER_NO + "')"), "ai-agent", "127.0.0.1");

        assertFalse(r.ok(), "只读事务里的 UPDATE 必须失败");
        assertTrue(String.valueOf(r.error()).contains("read-only transaction"),
                "期望 PG 的只读错误，实际：" + r.error());
        assertEquals("NEW", statusOf());
    }

    @Test
    @DisplayName("PG：提交后真的落库")
    void commitPersists() {
        ScriptResult r = harken.execute(new ReadWriteRequest(
                "def n = orderDao.markPaid('" + ORDER_NO + "')\n\"affected=${n}\"\n", true), "tester", "127.0.0.1");

        assertTrue(r.ok(), r.error());
        assertFalse(r.rolledBack());
        assertEquals("PAID", statusOf());
    }

    private String statusOf() {
        List<String> rows = jdbc.queryForList(
                "select status from demo_order where order_no = ?", String.class, ORDER_NO);
        return rows.isEmpty() ? "NOT_FOUND" : rows.get(0);
    }

    /** 被测业务 Bean：普通 JdbcTemplate 调用，不含任何 Harken API。 */
    public static class OrderDao {

        private final JdbcTemplate jdbc;

        public OrderDao(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public int markPaid(String orderNo) {
            return jdbc.update("update demo_order set status = 'PAID' where order_no = ? and status <> 'PAID'", orderNo);
        }

        public String statusOf(String orderNo) {
            List<String> rows = jdbc.queryForList(
                    "select status from demo_order where order_no = ?", String.class, orderNo);
            return rows.isEmpty() ? "NOT_FOUND" : rows.get(0);
        }
    }
}
