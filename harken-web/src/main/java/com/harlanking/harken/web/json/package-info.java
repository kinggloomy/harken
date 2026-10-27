/**
 * 极简 JSON 编解码。为「零外部依赖」这条产品红线服务，不是通用库。
 *
 * <p>为什么不用 Jackson：Harken 只为收发四个字段就要往使用者 classpath 里塞一个
 * 序列化框架，与「加一个依赖就能用、不会和你已有的 Jackson 版本打架」直接冲突。
 * 使用者自己的业务想用 Jackson 完全没问题，那是他们的事；Harken 不替他们决定。
 *
 * <p>边界很硬：只处理 Harken 自己的协议（UTF-8、无 BOM、嵌套 ≤ 32 层、不接受尾部垃圾）。
 * 不做类型推断、不做日期格式、不支持注释。不认识的东西一律报错。
 *
 * @author harlan
 * @since 1.0.0
 */
package com.harlanking.harken.web.json;
