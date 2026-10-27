/**
 * HTTP 层：把 {@link com.harlanking.harken.core.HarkenService} 暴露成两个互不相干的端点族。
 *
 * <h2>本模块的定位</h2>
 * <p>只依赖 Servlet API（{@code provided}），<b>不认识 Spring</b>。
 * 因此同一套代码可以挂在 Spring Boot 内嵌 Tomcat、裸 Jetty、外置 Tomcat 上。
 * Maven Enforcer 里有 {@code forbid-spring-in-web} 规则强制这一点。
 *
 * <h2>两个通道为什么是两套代码</h2>
 * <table border="1">
 *   <caption>通道对比</caption>
 *   <tr><th></th><th>人类通道 {@code /api/*}</th><th>AI 通道 {@code /ai/*}</th></tr>
 *   <tr><td>凭据</td><td>用户名密码 → 会话令牌</td><td>Bearer Token</td></tr>
 *   <tr><td>请求类型</td><td>{@code ReadWriteRequest}</td><td>{@code ReadOnlyRequest}</td></tr>
 *   <tr><td>能否提交事务</td><td>能（取决于角色）</td><td>不能（类型里没有这个概念）</td></tr>
 *   <tr><td>事务模式</td><td>COMMIT / ROLLBACK</td><td>READ_ONLY</td></tr>
 *   <tr><td>审计 operator</td><td>真实用户名</td><td>固定 {@code ai}</td></tr>
 * </table>
 *
 * <p>这不是「一个接口加个开关」，而是两套入口。代价是多写一个 servlet，
 * 收益是「AI 越权写库」这个风险在编译期就不存在。
 *
 * @author harlan
 * @since 1.0.0
 */
package com.harlanking.harken.web;
