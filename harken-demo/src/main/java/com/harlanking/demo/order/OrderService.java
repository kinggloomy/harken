package com.harlanking.demo.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 一个普通的业务 Bean。它可以被控制器调用，也可以被 Harken 脚本调用 ——
 * 对它来说两者没有区别，都是普通的方法调用。
 *
 * <p>方法故意分两类：读（{@link #count()} / {@link #statusOf(String)} /
 * {@link #topAmount(int)}）和写（{@link #markPaid(String)}）。
 * 演示要证明的核心只有一句：<b>写完不提交，事务外看到的是旧值。</b>
 *
 * @author harlan
 * @since 1.0.0
 */
@Service
public class OrderService {

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc Spring Boot 自动配置好的 JdbcTemplate
     */
    public OrderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 订单总数。
     *
     * @return 行数
     */
    public long count() {
        Long n = jdbc.queryForObject("select count(*) from demo_order", Long.class);
        return n == null ? 0L : n;
    }

    /**
     * 查一个订单的状态，不存在时返回 {@code NOT_FOUND}。
     *
     * <p>返回字符串而不是抛异常，是为了让脚本里一行就能用：
     * {@code orderService.statusOf('SO-1002')}。
     *
     * @param orderNo 订单号
     * @return 状态，例如 {@code NEW} / {@code PAID}
     */
    public String statusOf(String orderNo) {
        List<String> rows = jdbc.queryForList(
                "select status from demo_order where order_no = ?", String.class, orderNo);
        return rows.isEmpty() ? "NOT_FOUND" : rows.get(0);
    }

    /**
     * 把订单标记为已支付。已支付的不会再改，所以重复调用返回 0。
     *
     * <p>返回受影响行数是刻意的：演示和 M2 的「影响行数闸」都依赖这个返回值。
     *
     * @param orderNo 订单号
     * @return 受影响行数，0 表示订单不存在或已是 PAID
     */
    public int markPaid(String orderNo) {
        return jdbc.update("update demo_order set status = 'PAID' where order_no = ? and status <> 'PAID'", orderNo);
    }

    /**
     * 金额最高的前 N 笔订单，用于演示「脚本里拿一个集合，看 Harken 怎么序列化」。
     *
     * @param limit 最多返回多少行
     * @return 每行是一个 Map，键为列名
     */
    public List<Map<String, Object>> topAmount(int limit) {
        return jdbc.queryForList(
                "select order_no, customer, amount, status from demo_order order by amount desc limit ?", limit);
    }

    /**
     * 把所有订单一次改成已支付。<b>故意不写 where</b> —— 这就是控制台里最常见的那类事故：
     * 想改一行，结果改了全表。行数闸拿它演示「执行途中被拦下并回滚」。
     *
     * <p>刻意用无条件 update（而不是 {@code where status <> 'PAID'}），
     * 这样无论前面几步把数据改成什么样，影响行数恒等于总行数，
     * 演示与冒烟脚本的断言才不会受执行顺序影响。
     *
     * @return 受影响行数（正常情况下永远走不到这句 return）
     */
    public int markAllPaid() {
        return jdbc.update("update demo_order set status = 'PAID'");
    }

    /**
     * 关闭所有供应单。这张表在 {@code harken.guard.deny-tables} 里，
     * 所以这个调用会被守卫在执行前拦下。
     *
     * @return 受影响行数（正常情况下永远走不到这句 return）
     */
    public int closeAllSupplyOrders() {
        return jdbc.update("update supply_order set status = 'CLOSED'");
    }

    /**
     * 供应单数量。<b>黑名单只拦写，读必须放行</b> —— 否则连排查都做不了。
     *
     * @return 行数
     */
    public long supplyOrderCount() {
        Long n = jdbc.queryForObject("select count(*) from supply_order", Long.class);
        return n == null ? 0L : n;
    }

    /**
     * 建一张归档表。真实运维里「顺手在控制台改下表结构」是最常见的事故来源之一，
     * 所以拿它演示 DDL 拦截（{@code harken.guard.deny-ddl=true} 时会被拒）。
     */
    public void createArchiveTable() {
        jdbc.execute("create table demo_order_archive (id bigint primary key, order_no varchar(32))");
    }
}
