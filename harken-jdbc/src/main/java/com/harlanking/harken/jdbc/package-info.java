/**
 * 数据保护三件套：影响行数闸、关键表黑名单、独立事务审计。
 *
 * <p>这一层只认 {@code java.sql}，不认识 Spring，也不带任何数据库驱动 ——
 * 因此它既能挂在 Spring Boot 里，也能挂在纯 JDBC 的老系统里。
 *
 * <h2>三个类的分工</h2>
 * <table border="1">
 *   <caption>类与职责</caption>
 *   <tr><th>类</th><th>职责</th><th>谁会用到</th></tr>
 *   <tr><td>{@link com.harlanking.harken.jdbc.GuardPolicy}</td>
 *       <td>配置：行数上限、表黑名单、是否禁 DDL</td>
 *       <td>装配代码</td></tr>
 *   <tr><td>{@link com.harlanking.harken.jdbc.JdbcWriteGuard}</td>
 *       <td>计数与拦截，通过 {@code ThreadLocal} 跟着脚本走</td>
 *       <td>{@code HarkenService}</td></tr>
 *   <tr><td>{@link com.harlanking.harken.jdbc.GuardedDataSource}</td>
 *       <td>{@code DataSource} / {@code Connection} / {@code Statement} 代理</td>
 *       <td>Spring 的 {@code BeanPostProcessor} 或手工包装</td></tr>
 *   <tr><td>{@link com.harlanking.harken.jdbc.JdbcAuditStore}</td>
 *       <td>审计写库，独立连接独立提交</td>
 *       <td>{@code HarkenService} 的审计链</td></tr>
 * </table>
 *
 * <h2>必须接受的现实</h2>
 *
 * <p>守卫靠 SQL 文本判断，不是安全边界：存储过程、视图、触发器、动态拼接都能绕开。
 * 它防的是「少写一个 where」这类手滑，不防有心人。README 与 SECURITY.md 都必须写明这一点，
 * 否则它会给人错误的安全感 —— 那比没有这个功能更糟。
 *
 * @author harlan
 * @since 1.0.0
 */
package com.harlanking.harken.jdbc;
