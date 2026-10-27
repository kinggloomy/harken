/**
 * 扩展点（SPI）。使用者通过实现这些接口来改变 Harken 的行为，无需改源码。
 *
 * <h2>六个扩展点，按执行时机排列</h2>
 * <table border="1" cellpadding="6" cellspacing="0">
 *   <caption>扩展点职责</caption>
 *   <tr><th align="left">接口</th><th align="left">时机</th><th align="left">典型用途</th></tr>
 *   <tr><td>{@link com.harlanking.harken.core.spi.BeanResolver}</td><td>执行中（懒解析）</td><td>把容器里的 Bean 暴露给脚本</td></tr>
 *   <tr><td>{@link com.harlanking.harken.core.spi.BindingContributor}</td><td>执行前</td><td>注入当前用户、请求 ID、工具对象</td></tr>
 *   <tr><td>{@link com.harlanking.harken.core.spi.ScriptValidator}</td><td>执行前</td><td>公司内部禁用规则、脚本长度上限</td></tr>
 *   <tr><td>{@link com.harlanking.harken.core.spi.TransactionBridge}</td><td>执行中</td><td>把执行包进事务，默认回滚</td></tr>
 *   <tr><td>{@link com.harlanking.harken.core.spi.AuditStore}</td><td>执行后（事务外）</td><td>落日志 / 落库 / 发 MQ</td></tr>
 * </table>
 *
 * <h2>五条实现约定（违反会出线上问题）</h2>
 * <ol>
 *   <li><b>实现必须无状态、线程安全。</b> 扩展点是单例，脚本执行在多线程，同一个方法会被并发调用。</li>
 *   <li><b>所有方法都带 {@code default} 实现</b>，将来加方法不会破坏已有实现。</li>
 *   <li><b>排序用 {@code getOrder()}</b>，数字小的先执行。</li>
 *   <li><b>异常策略不统一</b>：{@code BindingContributor} / {@code ScriptValidator} /
 *       {@code TransactionBridge} 抛异常必须中断执行；{@code AuditStore} 抛异常必须被调用方吞掉，
 *       但要有 ERROR 日志——审计静默失败是最危险的状态。</li>
 *   <li><b>不要在这些接口里做耗时操作。</b> 它们位于请求路径上。</li>
 * </ol>
 *
 * @since 1.0.0
 */
package com.harlanking.harken.core.spi;
