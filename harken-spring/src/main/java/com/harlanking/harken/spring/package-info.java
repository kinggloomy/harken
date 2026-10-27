/**
 * Spring 适配层。四个类，各做一件事：
 *
 * <table border="1" cellpadding="6" cellspacing="0">
 *   <caption>Spring 适配层职责</caption>
 *   <tr><th align="left">类</th><th align="left">实现</th><th align="left">关键约束</th></tr>
 *   <tr>
 *     <td>{@link com.harlanking.harken.spring.SpringBeanResolver}</td>
 *     <td>{@link com.harlanking.harken.core.spi.BeanResolver}</td>
 *     <td>只用 {@code getType} 扫描，绝不 {@code getBean} 预实例化</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.harlanking.harken.spring.SpringTransactionBridge}</td>
 *     <td>{@link com.harlanking.harken.core.spi.TransactionBridge}</td>
 *     <td>默认 {@code REQUIRES_NEW}；只读 = {@code setRollbackOnly}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.harlanking.harken.spring.SpringHarken}</td>
 *     <td>—</td>
 *     <td>一行装配，自动收集扩展点 Bean</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.harlanking.harken.spring.ClasspathClassIndex}</td>
 *     <td>产出 {@link com.harlanking.harken.core.metadata.ClassIndex}</td>
 *     <td>{@code classpath*} 通配只由配置拼出，绝不由 HTTP 参数决定</td>
 *   </tr>
 * </table>
 *
 * <h2>这个模块存在的意义</h2>
 * <p>core 不认识 Spring（由 Maven Enforcer 强制）。所有 Spring 相关代码都收在这里，
 * 所以「非 Spring 项目也能用」不是一个承诺，而是构建层面的结构事实。
 *
 * <h2>关于补全元数据</h2>
 * <p>补全的数据结构 {@code ClassIndex}（类名索引）与 {@code MethodCatalog}（按需方法表）
 * 放在 core 的 {@code metadata} 包里，不在本模块 —— 它们只依赖
 * {@code BeanResolver} 接口，是框架无关的。
 *
 * <p>本模块负责的是「数据从哪来」两件事：Bean 从容器来（{@code SpringBeanResolver}），
 * 类名从 classpath 来（{@code ClasspathClassIndex}）。后者必须用 Spring 的资源解析器，
 * 因为它得同时应付 fat jar 与 exploded 两种形态 —— 这是它留在这里、不下沉到 core 的唯一原因。
 *
 * @since 1.0.0
 */
package com.harlanking.harken.spring;
