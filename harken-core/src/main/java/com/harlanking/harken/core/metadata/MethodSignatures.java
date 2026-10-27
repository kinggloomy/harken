package com.harlanking.harken.core.metadata;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 反射读取方法签名，并把补全列表里的噪音滤掉。
 *
 * <h2>实例方法与静态方法共用一份</h2>
 * <p>两侧的过滤方向相反（一个是「跳过 static」，一个是「只留 static」），
 * 但其余四件事完全一样：去掉 {@link Object} 自带的、去掉 bridge/synthetic、
 * 按「方法名 + 参数个数」去重、按名字排序并截断。
 * 这四件是补全列表能不能看的关键，分散在两处迟早会写歪一处。
 *
 * @author harlan
 * @since 1.0.0
 */
final class MethodSignatures {

    /** {@code max} 传了非法值时的兜底上限。 */
    private static final int FALLBACK_MAX = 200;

    private MethodSignatures() {
    }

    /**
     * 读取一个类型的方法签名。
     *
     * @param type       要读的类型，不能为 {@code null}
     * @param wantStatic {@code true} 只保留 {@code public static} 方法；
     *                   {@code false} 只保留实例方法
     * @param max        保留条数上限，小于 1 时按 200 处理
     * @return 按「方法名 → 参数个数」排序的方法清单，永不为 {@code null}
     */
    static List<MethodMetadata> of(Class<?> type, boolean wantStatic, int max) {
        int limit = max < 1 ? FALLBACK_MAX : max;
        List<MethodMetadata> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Method method : type.getMethods()) {
            // Object 的 11 个方法每个类型都带，300 个类型就是 3300 条纯噪音。
            // 只对实例方法判：静态那一侧本来就不可能命中 Object。
            if (!wantStatic && method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            int modifiers = method.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers) != wantStatic) {
                continue;
            }
            List<ParamMetadata> params = parametersOf(method);
            // 协变返回类型会生成同名同参的两个方法，按签名去重只留第一个
            if (!seen.add(method.getName() + "/" + params.size())) {
                continue;
            }
            result.add(new MethodMetadata(method.getName(), params,
                    method.getGenericReturnType().getTypeName()));
            if (result.size() >= limit) {
                break;
            }
        }

        result.sort((a, b) -> {
            int byName = a.name().compareTo(b.name());
            return byName != 0 ? byName : Integer.compare(a.parameters().size(), b.parameters().size());
        });
        return result;
    }

    private static List<ParamMetadata> parametersOf(Method method) {
        Parameter[] parameters = method.getParameters();
        List<ParamMetadata> result = new ArrayList<>(parameters.length);
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            String name = parameter.isNamePresent() ? parameter.getName() : "arg" + i;
            result.add(new ParamMetadata(name, parameter.getParameterizedType().getTypeName()));
        }
        return result;
    }
}
