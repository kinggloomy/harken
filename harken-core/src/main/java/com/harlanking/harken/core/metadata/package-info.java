/**
 * 补全元数据：类名索引与方法表，前端补全与 AI 能力清单的<b>同一份数据源</b>。
 *
 * <h2>一份数据，两个消费者</h2>
 * <ul>
 *   <li><b>前端补全</b>：敲 {@code import com.x.} 时列类名与子包，敲 {@code orderService.}
 *       时列方法。</li>
 *   <li><b>AI 能力清单</b>：AI 通过结构化接口发现「原来有 {@code markPaid} 这个方法」，
 *       不需要生成代码，也不需要被提供任意代码执行能力。</li>
 * </ul>
 *
 * <h2>为什么分两个类，而不是一个「快照」</h2>
 * <p>类名和方法名的获取成本差三个数量级：类名只要读 {@code .class} 的路径，
 * 方法名要建 {@link java.lang.Class}。混在一起设计，就会为了「一次拿全」而
 * 把几百个类全部加载进 Metaspace。所以 {@link com.harlanking.harken.core.metadata.ClassIndex}
 * 一次性建好（毫秒级），{@link com.harlanking.harken.core.metadata.MethodCatalog}
 * 等用户敲到点号再读一个类（微秒级）。
 *
 * <h2>为什么能做到 L4 级别的补全</h2>
 * <p>Groovy 是动态类型，{@code orderService.} 后面的点号处编译器不知道类型，
 * 静态分析无法推断。但 Harken 的脚本写法是「Bean 名直连」：
 * <pre>{@code
 * orderService.   // ← 这个名字在启动时就固定映射到 com.zm.supply.service.OrderService
 * }</pre>
 * 「变量名 → 类型」是一张启动时确定的静态表，补全不需要任何类型推断。
 * 这是 {@link com.harlanking.harken.core.engine.LazyGroovyBinding} 的直接收益。
 *
 * @since 1.0.0
 */
package com.harlanking.harken.core.metadata;
