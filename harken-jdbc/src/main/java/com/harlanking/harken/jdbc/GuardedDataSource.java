package com.harlanking.harken.jdbc;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * 给 {@link DataSource} 套一层「只记账、不改语义」的代理。
 *
 * <h2>为什么必须从 DataSource 下手</h2>
 *
 * <p>脚本调用的是业务 Bean（{@code orderService.markPaid()}），真正执行 SQL 的是
 * JdbcTemplate / MyBatis / JPA —— 它们在构造时就抓住了 {@code DataSource}。
 * 想数到行数，只有在数据源这一层把 {@code Connection} 换成代理，没有第二条路。
 *
 * <h2>代理只做三件事</h2>
 * <ol>
 *   <li>{@code prepareStatement / createStatement} 时把 SQL 送去做黑名单与 DDL 检查（快速失败）；</li>
 *   <li>{@code executeUpdate / execute / executeBatch} 之后把影响行数交给守卫累加；</li>
 *   <li>其余所有方法<b>原样转发</b>，包括 {@code unwrap}、{@code getMetaData}、
 *       连接池自己的方法。</li>
 * </ol>
 *
 * <p>参数、返回值类型、异常类型都不改变 —— 这是「不该让应用感觉到」的最低要求。
 * 被代理抛出的异常会剥掉 {@link InvocationTargetException} 外壳，
 * 原样抛给调用方，否则 Spring 的异常翻译、Groovy 的错误定位全都会失效。
 *
 * <h2>已知限制</h2>
 * <ul>
 *   <li>{@code unwrap()} 直通，所以拿到原生连接就能绕过守卫 —— 再次说明它不是安全边界；</li>
 *   <li>只包 {@code DataSource} 接口。如果代码把它强制转成 {@code HikariDataSource}，
 *       会 {@code ClassCastException}。用 {@link #unwrap(DataSource)} 取原始对象。</li>
 * </ul>
 *
 * @author harlan
 * @since 1.0.0
 */
public final class GuardedDataSource {

    private GuardedDataSource() {
    }

    /**
     * 包装数据源。已经是包装过的对象时直接返回，避免重复代理（会让行数翻倍）。
     *
     * @param target 原始数据源，不能为 {@code null}
     * @param guard  守卫，不能为 {@code null}
     * @return 包装后的数据源
     */
    public static DataSource wrap(DataSource target, JdbcWriteGuard guard) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(guard, "guard");
        if (isGuarded(target)) {
            return target;
        }
        return (DataSource) Proxy.newProxyInstance(
                GuardedDataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                new DataSourceHandler(target, guard));
    }

    /**
     * 判断是不是本类包装出来的代理。
     *
     * @param dataSource 数据源，可为 {@code null}
     * @return {@code true} 表示已包装
     */
    public static boolean isGuarded(DataSource dataSource) {
        return dataSource != null
                && Proxy.isProxyClass(dataSource.getClass())
                && Proxy.getInvocationHandler(dataSource) instanceof DataSourceHandler;
    }

    /**
     * 取出原始数据源。审计写入必须走原始数据源，否则它自己也会被计入行数、
     * 甚至被黑名单拦住 —— 那才是最讽刺的死法。
     *
     * @param dataSource 数据源，可为 {@code null}
     * @return 原始数据源；本来就没包装时原样返回
     */
    public static DataSource unwrap(DataSource dataSource) {
        if (isGuarded(dataSource)) {
            return ((DataSourceHandler) Proxy.getInvocationHandler(dataSource)).target;
        }
        return dataSource;
    }

    // ------------------------------------------------------------------
    // 三个 InvocationHandler
    // ------------------------------------------------------------------

    /**
     * 数据源代理：只拦 {@code getConnection}。
     */
    private static final class DataSourceHandler implements InvocationHandler {

        private final DataSource target;
        private final JdbcWriteGuard guard;

        private DataSourceHandler(DataSource target, JdbcWriteGuard guard) {
            this.target = target;
            this.guard = guard;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "GuardedDataSource(" + target + ")";
                case "unwrap":
                case "isWrapperFor":
                    return call(target, method, args);
                case "getConnection":
                    return wrapConnection((Connection) call(target, method, args), guard);
                default:
                    return call(target, method, args);
            }
        }
    }

    /**
     * 连接代理：拦新建语句的入口。
     */
    private static final class ConnectionHandler implements InvocationHandler {

        private final Connection target;
        private final JdbcWriteGuard guard;

        private ConnectionHandler(Connection target, JdbcWriteGuard guard) {
            this.target = target;
            this.guard = guard;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "GuardedConnection(" + target + ")";
                case "unwrap":
                case "isWrapperFor":
                    return call(target, method, args);
                case "createStatement":
                    return wrapStatement((Statement) call(target, method, args), null, guard);
                case "prepareStatement": {
                    String sql = firstString(args);
                    // 在 prepare 阶段就检查：语句还没碰数据库，拒绝的代价最小
                    guard.check(sql);
                    return wrapStatement((Statement) call(target, method, args), sql, guard);
                }
                case "prepareCall":
                    // 调存储过程时看不到内部 SQL，只能不检查 —— 这是已知缺口
                    return wrapStatement((Statement) call(target, method, args), null, guard);
                default:
                    return call(target, method, args);
            }
        }
    }

    /**
     * 语句代理：检查 SQL、累计影响行数。
     */
    private static final class StatementHandler implements InvocationHandler {

        private final Statement target;
        private final JdbcWriteGuard guard;
        /** 由 {@code prepareStatement} 带进来的 SQL；普通 Statement 为 {@code null}。 */
        private final String sql;

        private StatementHandler(Statement target, String sql, JdbcWriteGuard guard) {
            this.target = target;
            this.sql = sql;
            this.guard = guard;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "GuardedStatement(" + target + ")";
                case "unwrap":
                case "isWrapperFor":
                    return call(target, method, args);

                case "executeUpdate":
                case "executeLargeUpdate": {
                    String effective = effectiveSql(args);
                    guard.check(effective);
                    Object result = call(target, method, args);
                    guard.count(effective, toLong(result));
                    return result;
                }

                case "execute": {
                    String effective = effectiveSql(args);
                    guard.check(effective);
                    Object result = call(target, method, args);
                    // execute() 返回 true = 结果集，false = 更新计数，需要再问一次
                    if (Boolean.FALSE.equals(result)) {
                        int count = updateCountOf(target);
                        if (count > 0) {
                            guard.count(effective, count);
                        }
                    }
                    return result;
                }

                case "addBatch": {
                    // 批量语句的 SQL 在 addBatch 时就已知，提前检查
                    String effective = effectiveSql(args);
                    guard.check(effective);
                    return call(target, method, args);
                }

                case "executeBatch":
                case "executeLargeBatch": {
                    Object result = call(target, method, args);
                    guard.count(sql, sumOf(result));
                    return result;
                }

                default:
                    return call(target, method, args);
            }
        }

        private String effectiveSql(Object[] args) {
            return sql != null ? sql : firstString(args);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static Connection wrapConnection(Connection connection, JdbcWriteGuard guard) {
        if (connection == null || !guard.isActive()) {
            // 作用域外（应用自身的 SQL、启动脚本）零开销直通：不代理，也不解析
            return connection;
        }
        return (Connection) Proxy.newProxyInstance(
                GuardedDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionHandler(connection, guard));
    }

    private static Statement wrapStatement(Statement statement, String sql, JdbcWriteGuard guard) {
        if (statement == null || !guard.isActive()) {
            return statement;
        }
        Class<?>[] iface;
        if (statement instanceof CallableStatement) {
            iface = new Class<?>[]{CallableStatement.class};
        } else if (statement instanceof PreparedStatement) {
            iface = new Class<?>[]{PreparedStatement.class};
        } else {
            iface = new Class<?>[]{Statement.class};
        }
        return (Statement) Proxy.newProxyInstance(
                GuardedDataSource.class.getClassLoader(), iface,
                new StatementHandler(statement, sql, guard));
    }

    /**
     * 反射调用目标对象，剥掉 {@link InvocationTargetException} 外壳。
     */
    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }

    private static String firstString(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof String s) {
                return s;
            }
        }
        return null;
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private static long sumOf(Object value) {
        if (value instanceof int[] ints) {
            long sum = 0;
            for (int v : ints) {
                if (v > 0) {
                    sum += v;
                }
            }
            return sum;
        }
        if (value instanceof long[] longs) {
            long sum = 0;
            for (long v : longs) {
                if (v > 0) {
                    sum += v;
                }
            }
            return sum;
        }
        return 0L;
    }

    private static int updateCountOf(Statement statement) {
        try {
            return statement.getUpdateCount();
        } catch (SQLException | RuntimeException e) {
            // 驱动不支持就算了：这只是「execute() 之后补问一句」，不能因为问不到就报错
            return -1;
        }
    }
}
