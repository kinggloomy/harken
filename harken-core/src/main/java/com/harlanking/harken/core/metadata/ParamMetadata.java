package com.harlanking.harken.core.metadata;

/**
 * 方法参数。参数名依赖编译期 {@code -parameters}（本项目 Maven 已开启）。
 *
 * <p>如果使用者自己的业务代码没有开 {@code -parameters}，参数名会退化成
 * {@code arg0} / {@code arg1}。这不影响补全，但会让 AI 无法按名字填参数，
 * 只能按类型猜。这是使用者的编译配置问题，不是 Harken 的问题。
 *
 * @param name 参数名，缺少调试信息时为 {@code argN}
 * @param type 参数类型，泛型信息保留
 * @author harlan
 * @since 1.0.0
 */
public record ParamMetadata(String name, String type) {
}
