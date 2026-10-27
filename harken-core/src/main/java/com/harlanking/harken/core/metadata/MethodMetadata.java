package com.harlanking.harken.core.metadata;

import java.util.List;

/**
 * 一个 public 方法的签名。
 *
 * <p>只保留补全与结构化调用需要的信息，不保留 {@link java.lang.reflect.Method} 引用
 * ——那会把 ClassLoader 钉住，导致热部署后旧类无法卸载。
 *
 * @param name       方法名
 * @param parameters 参数列表，按声明顺序
 * @param returnType 返回类型，泛型信息保留（例如 {@code java.util.List<com.zm.Order>}）
 * @author harlan
 * @since 1.0.0
 */
public record MethodMetadata(
        String name,
        List<ParamMetadata> parameters,
        String returnType
) {

    /**
     * @param name       方法名
     * @param parameters 参数列表，会被防御性复制
     * @param returnType 返回类型文本
     */
    public MethodMetadata {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
