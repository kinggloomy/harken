/**
 * 内置实现。这些都是「没有外部依赖也能跑」的兜底版本，可被使用者替换。
 *
 * <table border="1" cellpadding="6" cellspacing="0">
 *   <caption>内置实现一览</caption>
 *   <tr><th align="left">类</th><th align="left">替代什么</th><th align="left">何时用</th></tr>
 *   <tr><td>{@link com.harlanking.harken.core.impl.Slf4jAuditStore}</td><td>JDBC 审计表</td><td>默认。零建表，写 {@code HARKEN_AUDIT} logger</td></tr>
 *   <tr><td>{@link com.harlanking.harken.core.impl.MemoryAuditStore}</td><td>—</td><td>要在控制台里反查「最近执行」，又不想建表</td></tr>
 *   <tr><td>{@link com.harlanking.harken.core.impl.CompositeAuditStore}</td><td>—</td><td>日志 + 数据库 + 内存一起挂</td></tr>
 *   <tr><td>{@link com.harlanking.harken.core.impl.NoopAuditStore}</td><td>—</td><td>开发环境，不想留痕</td></tr>
 *   <tr><td>{@link com.harlanking.harken.core.impl.MapBeanResolver}</td><td>Spring BeanFactory</td><td>非 Spring 项目、单元测试</td></tr>
 *   <tr><td>{@link com.harlanking.harken.core.impl.DefaultScriptValidator}</td><td>—</td><td>永远生效，不可关闭</td></tr>
 * </table>
 *
 * @since 1.0.0
 */
package com.harlanking.harken.core.impl;
