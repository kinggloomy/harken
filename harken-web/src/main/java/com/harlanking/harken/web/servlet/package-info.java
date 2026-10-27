/**
 * Servlet 与过滤器。每个 servlet 对应一个通道，不做「一个入口内部 if 分流」。
 *
 * <h2>为什么路由是手写的 switch</h2>
 * <p>没有 Spring MVC 的 {@code @RequestMapping}，所以要自己按 {@code pathInfo} 分发。
 * 五个端点的规模下，一个 {@code switch} 比引入注解处理器更透明：
 * 想知道某个 URL 干什么，看这一个方法就够了。
 *
 * <p>不用 Spring 的代价是「路由要自己写」，收益是「没有 Spring 的应用也能用」。
 *
 * @author harlan
 * @since 1.0.0
 */
package com.harlanking.harken.web.servlet;
