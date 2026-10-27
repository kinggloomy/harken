/**
 * Harken 的入口与编排层。
 *
 * <h2>这个包只有四件事</h2>
 * <ol>
 *   <li>{@link com.harlanking.harken.core.HarkenService} —— 唯一的编排者。整条流水线
 *       （长度检查 → 校验 → 事务决策 → 注入变量 → 事务内执行 → 截断 → 审计）都在
 *       {@code doExecute} 里，读这一个方法就能理解全部行为。</li>
 *   <li>{@link com.harlanking.harken.core.HarkenOptions} —— 执行参数，不可变。</li>
 *   <li>{@link com.harlanking.harken.core.HarkenRuntime} —— 非 Spring 场景的编程式装配入口。</li>
 *   <li>{@link com.harlanking.harken.core.HarkenException} —— 异常族，错误类型靠 {@code code} 区分。</li>
 * </ol>
 *
 * <h2>为什么 core 里没有 Spring</h2>
 * <p>core 只认识 {@link com.harlanking.harken.core.spi} 下的六个接口。Spring 适配在
 * {@code harken-spring} 里通过实现这些接口完成。这条边界由 Maven Enforcer 强制校验，
 * 一旦有人在 core 里 {@code import org.springframework}，构建会直接失败。</p>
 *
 * @since 1.0.0
 */
package com.harlanking.harken.core;
