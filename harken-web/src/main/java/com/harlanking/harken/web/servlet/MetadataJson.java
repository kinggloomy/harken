package com.harlanking.harken.web.servlet;

import com.harlanking.harken.core.metadata.ClassIndex;
import com.harlanking.harken.core.metadata.MethodCatalog;
import com.harlanking.harken.core.metadata.MethodMetadata;
import com.harlanking.harken.core.metadata.ParamMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把补全元数据转成 JSON 友好的结构。
 *
 * <h2>为什么不在 record 上加 Jackson 注解</h2>
 * <p>core 模块必须零依赖，不能在元数据类型上挂 {@code @JsonProperty}。
 * 转换放在 HTTP 层，两边都干净。
 *
 * <h2>字段命名</h2>
 * <p>刻意短：{@code returns} 而不是 {@code returnTypeName}，{@code params} 而不是
 * {@code parameterList}。这份 JSON 会被 AI 客户端整段读进上下文，
 * 每个字段名的长度都要乘以方法数。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class MetadataJson {

    private MetadataJson() {
    }

    /**
     * 按参数选择要返回的视图。五个参数互斥，按
     * {@code type → inst → bean → jdk → 索引} 的顺序取第一个非空的。
     *
     * <p>放在这里而不是各自在 servlet 里写一遍，是因为人类通道和 AI 通道要的是
     * <b>同一份</b>数据 —— 两个入口各写一套分发，迟早出现「控制台有提示、AI 没有」。
     *
     * @param index       类名索引
     * @param catalog     方法表
     * @param type        类名。带点号是全限定名；不带点号是脚本里没 import 的简名
     * @param inst        类的全限定名，给实例方法（链式补全的第二段用）
     * @param beanName    Bean 名，查实例方法
     * @param jdkPackage  JDK 包名，查该类名
     * @return 可直接序列化的 Map
     */
    public static Map<String, Object> view(ClassIndex index, MethodCatalog catalog, String type,
                                           String inst, String beanName, String jdkPackage) {
        if (hasText(type)) {
            return typeView(index, catalog, type.trim());
        }
        if (hasText(inst)) {
            return typeMethods(catalog, inst.trim(), false);
        }
        if (hasText(beanName)) {
            return methods(beanName.trim(), false, catalog.instanceMethods(beanName.trim()));
        }
        if (hasText(jdkPackage)) {
            String pkg = jdkPackage.trim();
            return jdkClasses(pkg, index.jdkClassesIn(pkg));
        }
        return indexView(index, catalog.beanTypes());
    }

    /**
     * 类名视图。两种形态：
     *
     * <ul>
     *   <li><b>带点号</b>：全限定名，说明脚本里已经 import 过它，直接给方法表。</li>
     *   <li><b>不带点号</b>：脚本里没 import 的简名。方法表给的是「你本来想要的那个类」
     *       （从索引反查），另附 {@code shadowedBy} 告诉你这个名字现在实际绑到哪个
     *       默认导入类 —— 前端据此决定是「补个 import」还是「你绑错类了」。</li>
     * </ul>
     */
    private static Map<String, Object> typeView(ClassIndex index, MethodCatalog catalog, String type) {
        if (type.indexOf('.') >= 0) {
            return typeMethods(catalog, type, true);
        }
        String wanted = index.fqcnOf(type);
        Map<String, Object> view = wanted == null
                ? methods(type, true, List.of())
                : typeMethods(catalog, wanted, true);
        String shadow = catalog.defaultImportOf(type);
        if (shadow != null) {
            view.put("shadowedBy", shadow);
        }
        return view;
    }

    /**
     * 类型视图：方法表 + 类型参数名，静态侧另加枚举常量。
     *
     * <p>枚举常量放这里是因为它是 {@code valueOf} 唯一接受的写法，而它不是方法 ——
     * 漏掉它，用户会在 {@code Hexagram.} 后面找不到任何可用的常量名。
     */
    private static Map<String, Object> typeMethods(MethodCatalog catalog, String fqcn,
                                                   boolean wantStatic) {
        List<MethodMetadata> list = wantStatic
                ? catalog.staticMethods(fqcn) : catalog.instanceMethodsOf(fqcn);
        Map<String, Object> view = methods(fqcn, wantStatic, list);
        List<String> typeVars = catalog.typeVarsOf(fqcn);
        if (!typeVars.isEmpty()) {
            view.put("typeVars", typeVars);
        }
        if (wantStatic) {
            List<String> constants = catalog.constantsOf(fqcn);
            if (!constants.isEmpty()) {
                view.put("constants", constants);
            }
        }
        return view;
    }

    private static Map<String, Object> indexView(ClassIndex index, Map<String, String> beanTypes) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("classCount", index.size());
        root.put("beanCount", beanTypes.size());
        root.put("byPackage", index.byPackage());
        root.put("beanTypes", beanTypes);
        return root;
    }

    private static Map<String, Object> methods(String owner, boolean isStatic,
                                               List<MethodMetadata> methods) {
        List<Object> list = new ArrayList<>(methods.size());
        for (MethodMetadata method : methods) {
            list.add(methodToMap(method));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("owner", owner);
        root.put("static", isStatic);
        root.put("methodCount", methods.size());
        root.put("methods", list);
        return root;
    }

    private static Map<String, Object> jdkClasses(String pkg, List<String> classes) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("package", pkg);
        root.put("classCount", classes.size());
        root.put("classes", classes);
        return root;
    }

    private static Map<String, Object> methodToMap(MethodMetadata method) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", method.name());
        List<Object> params = new ArrayList<>(method.parameters().size());
        for (ParamMetadata p : method.parameters()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("type", p.type());
            params.add(pm);
        }
        m.put("params", params);
        m.put("returns", method.returnType());
        return m;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
