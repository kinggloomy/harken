/**
 * 预置脚本目录（受限模式的数据来源）。
 *
 * <p>这个包只定义「脚本从哪来、长什么样」，不关心怎么执行。执行引擎通过
 * {@link com.harlanking.harken.core.HarkenService#catalog()} 拿到目录，
 * 受限模式用它替代自由编辑器。
 *
 * <p>JSON 解析用 Groovy 自带的 {@code groovy.json.JsonSlurper}，没有引入额外依赖。
 *
 * @since 1.0.0
 */
package com.harlanking.harken.core.catalog;
