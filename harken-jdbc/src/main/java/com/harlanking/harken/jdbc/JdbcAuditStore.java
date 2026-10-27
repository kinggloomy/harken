package com.harlanking.harken.jdbc;

import com.harlanking.harken.core.spi.AuditRecord;
import com.harlanking.harken.core.spi.AuditStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把审计写进数据库表。用<b>独立连接</b>，因此脚本回滚时审计记录照样留下。
 *
 * <h2>为什么必须独立连接</h2>
 *
 * <p>审计写在业务事务里，脚本一 {@code rollback} 审计就跟着没了 —— 而「被回滚的那一次」
 * 恰恰是最需要查的：有人试图改 200 行、被行数闸拦下、事务回滚，结果什么记录都没有，
 * 这比不装审计更糟，因为它给人「有审计」的错觉。
 *
 * <p>所以这里从 {@code DataSource} 另取一条连接，关掉自动提交、插入、提交、归还。
 * 它与脚本的事务毫无关系，天然满足「独立事务」。代价是<b>连接池至少要能同时给出两条连接</b>
 * （脚本一条、审计一条），池子大小为 1 时会阻塞 —— 这一点写在文档里。
 *
 * <h2>不经过写入守卫</h2>
 *
 * <p>构造时会把 {@link GuardedDataSource} 拆掉。否则审计插入会被自己计入行数，
 * 配了 {@code deny-tables} 时甚至可能把自己拦下 —— 那就成了笑话。
 *
 * <h2>建表</h2>
 *
 * <p>{@code autoCreate=true} 时首次写入前执行一次
 * {@code create table if not exists}。支持的方言：H2 / PostgreSQL / MySQL 8。
 * Oracle 的 {@code boolean} 与无 {@code if not exists} 语法不兼容，请关掉自动建表、
 * 自己建表后把 {@code harken.audit.jdbc.auto-create} 设为 {@code false}。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class JdbcAuditStore implements AuditStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditStore.class);

    /** 表名白名单：只允许普通标识符与 {@code schema.table}，堵住通过配置注入 SQL 的路。 */
    private static final Pattern TABLE_NAME =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?$");

    /** 脚本原文入库前截断到这个字符数。真实脚本很少超过，超了也只看开头。 */
    private static final int MAX_SCRIPT_CHARS = 4_000;

    private static final int MAX_ERROR_CHARS = 2_000;

    private static final String COLUMNS =
            "trace_id, executed_at, channel, operator_name, client_ip, script_digest, script,"
                    + " commit_requested, rolled_back, ok, error_type, error_msg,"
                    + " result_digest, duration_ms, affected_rows";

    private static final String SELECT_COLUMNS =
            "select trace_id, executed_at, channel, operator_name, client_ip, script_digest, script,"
                    + " commit_requested, rolled_back, ok, error_type, error_msg,"
                    + " result_digest, duration_ms, affected_rows from ";

    private final DataSource dataSource;
    private final String table;
    private final boolean autoCreate;

    private volatile boolean schemaReady;

    /**
     * @param dataSource 数据源；若传进来的是 {@link GuardedDataSource} 的代理，会自动拆掉
     * @param table      表名，必须是合法标识符（可带 schema 前缀）
     * @param autoCreate 是否自动建表
     * @throws IllegalArgumentException 表名为空或不是合法标识符
     */
    public JdbcAuditStore(DataSource dataSource, String table, boolean autoCreate) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        if (table == null || !TABLE_NAME.matcher(table.trim()).matches()) {
            throw new IllegalArgumentException("非法表名：" + table
                    + "（只允许字母、数字、下划线、$，可带一个 schema 前缀）");
        }
        this.dataSource = GuardedDataSource.unwrap(dataSource);
        this.table = table.trim();
        this.autoCreate = autoCreate;
    }

    /**
     * @return 例如 {@code JdbcAuditStore(harken_audit)}
     */
    @Override
    public String name() {
        return "JdbcAuditStore(" + table + ")";
    }

    /**
     * @return 恒为 {@code true}
     */
    @Override
    public boolean supportsQuery() {
        return true;
    }

    /**
     * 插入一条审计记录。独立连接 + 独立提交。
     *
     * <p>本方法<b>不吞异常</b>：调用方（{@code HarkenService#writeAudit}）已经统一兜住并打 ERROR。
     * 在这里再吞一次会让「审计写失败」彻底消失。
     *
     * @param record 审计记录
     * @throws IllegalStateException 写入失败
     */
    @Override
    public void save(AuditRecord record) {
        ensureSchema();
        String sql = "insert into " + table + " (" + COLUMNS + ") values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(ps, record);
                ps.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                safeRollback(connection);
                throw e;
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("审计写入失败：" + table + "：" + e.getMessage(), e);
        }
    }

    /**
     * 最近的审计记录，按时间倒序。
     *
     * @param limit 最多返回条数，会被夹在 1~1000
     * @return 记录列表，永不为 {@code null}
     */
    @Override
    public List<AuditRecord> query(int limit) {
        int n = Math.max(1, Math.min(limit, 1000));
        // limit 是我们自己夹过的整数，直接拼进 SQL 是安全的，也避开了各家驱动对 limit ? 的差异
        String sql = SELECT_COLUMNS + table + " order by executed_at desc limit " + n;
        List<AuditRecord> out = new ArrayList<>(Math.min(n, 64));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                out.add(read(rs));
            }
        } catch (SQLException e) {
            log.error("Harken 审计查询失败：table={} limit={}", table, n, e);
            return List.of();
        }
        return out;
    }

    // ------------------------------------------------------------------

    private void bind(PreparedStatement ps, AuditRecord r) throws SQLException {
        ps.setString(1, r.traceId());
        ps.setTimestamp(2, r.at() == null ? null : Timestamp.from(r.at()));
        ps.setString(3, r.channel());
        ps.setString(4, r.operator());
        ps.setString(5, r.clientIp());
        ps.setString(6, r.scriptDigest());
        ps.setString(7, trim(r.script(), MAX_SCRIPT_CHARS));
        ps.setBoolean(8, r.commitRequested());
        ps.setBoolean(9, r.rolledBack());
        ps.setBoolean(10, r.ok());
        ps.setString(11, r.errorType());
        ps.setString(12, trim(r.errorMsg(), MAX_ERROR_CHARS));
        ps.setString(13, r.resultDigest());
        ps.setLong(14, r.durationMs());
        ps.setLong(15, r.affectedRows());
    }

    private AuditRecord read(ResultSet rs) throws SQLException {
        Timestamp at = rs.getTimestamp("executed_at");
        return AuditRecord.builder()
                .traceId(rs.getString("trace_id"))
                .at(at == null ? null : at.toInstant())
                .channel(rs.getString("channel"))
                .operator(rs.getString("operator_name"))
                .clientIp(rs.getString("client_ip"))
                .scriptDigest(rs.getString("script_digest"))
                .script(rs.getString("script"))
                .commitRequested(rs.getBoolean("commit_requested"))
                .rolledBack(rs.getBoolean("rolled_back"))
                .ok(rs.getBoolean("ok"))
                .errorType(rs.getString("error_type"))
                .errorMsg(rs.getString("error_msg"))
                .resultDigest(rs.getString("result_digest"))
                .durationMs(rs.getLong("duration_ms"))
                .affectedRows(rs.getLong("affected_rows"))
                .build();
    }

    /**
     * 首次写入前建表。失败只打日志、不抛：表可能已经存在但没有建表权限。
     */
    private void ensureSchema() {
        if (schemaReady || !autoCreate) {
            return;
        }
        synchronized (this) {
            if (schemaReady) {
                return;
            }
            String ddl = "create table if not exists " + table + " ("
                    + "trace_id varchar(32) not null,"
                    + " executed_at timestamp not null,"
                    + " channel varchar(16),"
                    + " operator_name varchar(120),"
                    + " client_ip varchar(64),"
                    + " script_digest varchar(80),"
                    + " script varchar(" + MAX_SCRIPT_CHARS + "),"
                    + " commit_requested boolean,"
                    + " rolled_back boolean,"
                    + " ok boolean,"
                    + " error_type varchar(80),"
                    + " error_msg varchar(" + MAX_ERROR_CHARS + "),"
                    + " result_digest varchar(120),"
                    + " duration_ms bigint,"
                    + " affected_rows bigint"
                    + ")";
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(ddl);
                schemaReady = true;
            } catch (SQLException e) {
                // 不置 schemaReady：下次写入还会重试，避免一次网络抖动让审计永久失效
                log.error("Harken 审计建表失败（下次写入会重试）：{}", ddl, e);
            }
        }
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("Harken 审计回滚失败", e);
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean original) {
        try {
            if (connection.getAutoCommit() != original) {
                connection.setAutoCommit(original);
            }
        } catch (SQLException e) {
            log.warn("Harken 恢复 autoCommit 失败，连接归还时可能被池重置", e);
        }
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
