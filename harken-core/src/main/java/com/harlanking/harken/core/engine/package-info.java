/**
 * 脚本引擎层：编译、缓存、绑定、输出捕获、超时执行。
 *
 * <h2>一次执行的完整链路</h2>
 * <pre>
 * ScriptExecutor.call(timeout)
 *   → Thread(harken-exec-N)
 *     → TransactionBridge.inTransaction(readOnly)      ← 必须在 worker 线程内，见下
 *       → GroovyScriptEngine.run
 *         → compile(script)  [LRU 缓存命中则跳过]
 *         → newInstance + LazyGroovyBinding + setHarkenOut
 *         → Script.run()
 * </pre>
 *
 * <h2>三个已踩过的坑（改代码前必读）</h2>
 * <ol>
 *   <li><b>Groovy 4 没有 {@code Script#setOut}。</b> 想捕获 {@code println} 只能自定义基类，
 *       见 {@link com.harlanking.harken.core.engine.HarkenScriptBase}。</li>
 *   <li><b>{@code Binding(Map)} 直接持有传入的 Map 引用</b>，不复制。传不可变 Map 进去，
 *       后续 {@code setVariable} 会抛 {@code UnsupportedOperationException}。</li>
 *   <li><b>编译产物永不卸载。</b> 同一个源码必须复用同一个 Class，否则 Metaspace 会被跑满。</li>
 * </ol>
 *
 * @since 1.0.0
 */
package com.harlanking.harken.core.engine;
