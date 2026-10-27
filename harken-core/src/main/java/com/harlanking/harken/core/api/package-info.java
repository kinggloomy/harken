/**
 * 对外请求 / 响应契约。
 *
 * <h2>为什么请求是 sealed 的</h2>
 * <p>{@link com.harlanking.harken.core.api.ScriptRequest} 只允许两个实现：
 * {@link com.harlanking.harken.core.api.ReadOnlyRequest}（AI 通道，<b>没有 commit 字段</b>）与
 * {@link com.harlanking.harken.core.api.ReadWriteRequest}（人类通道）。
 *
 * <p>如果改成 {@code execute(String script, boolean commit)}，那么「AI 不能写库」就退化为
 * 运行时的一次 if 判断——任何一处漏判、任何一个新增入口忘了检查，都会变成数据事故。
 * 用类型表达约束，让编译器帮忙守这条线。
 *
 * <h2>响应为什么区分 result 与 output</h2>
 * <p>{@code result} 是脚本 {@code return} 的值，{@code output} 是 {@code println} 的内容。
 * 混在一起时，调用方（尤其是 AI）无法判断哪个是答案、哪个是调试噪音。
 *
 * @since 1.0.0
 */
package com.harlanking.harken.core.api;
