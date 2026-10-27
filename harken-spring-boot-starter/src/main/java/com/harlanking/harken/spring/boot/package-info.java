/**
 * Spring Boot 自动配置。加一个依赖，{@code HarkenService} 就进容器了。
 *
 * <h2>为什么这个模块可以很薄</h2>
 * <p>真正的逻辑都在 {@code harken-core} 与 {@code harken-spring}。这里只做三件事：
 * 绑定 {@code harken.*} 配置、按条件注册 Bean、启动时把安全状态打进日志。
 * 一个「自动配置类膨胀到几百行」的项目，通常意味着核心抽象没抽干净。
 *
 * <h2>默认姿态</h2>
 * <ul>
 *   <li>默认启用（{@code matchIfMissing = true}），但网络层在 M2 才落地且只绑回环地址。</li>
 *   <li>没有事务管理器时，写操作一律拒绝 —— 不是提示，是拒绝。</li>
 *   <li>{@code harken.packages} 留空会打 WARN，因为暴露范围过宽。</li>
 * </ul>
 *
 * @since 1.0.0
 */
package com.harlanking.harken.spring.boot;
