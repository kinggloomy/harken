package com.harlanking.harken.core;

import com.harlanking.harken.core.impl.MapBeanResolver;
import com.harlanking.harken.core.metadata.MethodCatalog;
import com.harlanking.harken.core.metadata.MethodMetadata;
import com.harlanking.harken.core.metadata.ParamMetadata;
import com.harlanking.harken.core.spi.BeanResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方法表的行为约定。这些断言对应 UI 补全的实际体验：任何一条被破坏，
 * 列表就会变成噪音，或者「点了就报错」。
 */
class MethodCatalogTest {

    /** 被描述的业务 Bean。方法故意包含重载、静态方法与继承自 Object 的方法。 */
    static class OrderService {

        public long count() {
            return 42;
        }

        public String queryById(Long id, boolean withItems) {
            return "order-" + id + "-" + withItems;
        }

        public String queryById(Long id) {
            return "order-" + id;
        }

        public static String staticUtil() {
            return "should-not-appear";
        }
    }

    /** 记录静态初始化是否被执行过。触发点在另一个类里，这样读它不会连带初始化被测类。 */
    static final class Marker {
        static volatile String initializedBy = "none";

        private Marker() {
        }
    }

    /** 只有两个常量，用来验证枚举常量清单的顺序与内容。 */
    enum TwoConstants {
        A, B
    }

    /** 故意带静态初始化块：读方法签名绝不该顺带跑它。 */
    static class HasStaticInit {

        static {
            Marker.initializedBy = "HasStaticInit";
        }

        public static String hello() {
            return "hi";
        }
    }

    private static MethodCatalog catalogOf(String beanName, Object bean) {
        return new MethodCatalog(new MapBeanResolver(Map.of(beanName, bean)));
    }

    @Test
    void readsInstanceMethodsAndSkipsObjectOnes() {
        MethodCatalog catalog = catalogOf("orderService", new OrderService());

        assertEquals(Map.of("orderService", OrderService.class.getName()), catalog.beanTypes());

        List<String> names = catalog.instanceMethods("orderService").stream()
                .map(MethodMetadata::name)
                .toList();
        assertTrue(names.contains("count"));
        assertTrue(names.contains("queryById"));
        // Object 自带的方法必须被剔除，否则每个 Bean 白送 11 条噪音
        assertFalse(names.contains("equals"));
        assertFalse(names.contains("hashCode"));
        assertFalse(names.contains("wait"));
        // 用实例调静态方法是误导，它的补全入口是类型名
        assertFalse(names.contains("staticUtil"));
    }

    @Test
    void keepsParameterNamesForAiAndCompletion() {
        MethodCatalog catalog = catalogOf("orderService", new OrderService());

        MethodMetadata query = catalog.instanceMethods("orderService").stream()
                .filter(m -> m.name().equals("queryById") && m.parameters().size() == 2)
                .findFirst()
                .orElseThrow();

        List<ParamMetadata> params = query.parameters();
        // 依赖编译期 -parameters；没有它这里会退化成 arg0/arg1
        assertEquals("id", params.get(0).name());
        assertEquals("withItems", params.get(1).name());
        assertEquals("java.lang.Long", params.get(0).type());
        assertEquals("java.lang.String", query.returnType());
    }

    @Test
    void keepsOverloadsSeparately() {
        MethodCatalog catalog = catalogOf("orderService", new OrderService());

        long overloads = catalog.instanceMethods("orderService").stream()
                .filter(m -> m.name().equals("queryById"))
                .count();

        assertEquals(2, overloads);
    }

    @Test
    void readsStaticMethodsOfAnyType() {
        MethodCatalog catalog = new MethodCatalog(BeanResolver.empty());

        List<String> names = catalog.staticMethods("java.util.UUID").stream()
                .map(MethodMetadata::name)
                .toList();

        assertTrue(names.contains("randomUUID"));
        // 实例方法不属于静态那一侧
        assertFalse(names.contains("toString"));
    }

    @Test
    void unknownBeanYieldsEmptyList() {
        MethodCatalog catalog = catalogOf("orderService", new OrderService());

        assertTrue(catalog.instanceMethods("nope").isEmpty());
        assertTrue(catalog.instanceMethods(null).isEmpty());
    }

    /**
     * 链式补全的地基：{@code allViews()} 返回 {@code List<X>}，第二个点号后面要列的是
     * {@code List} 的实例方法。
     */
    @Test
    void readsInstanceMethodsOfAType() {
        MethodCatalog catalog = new MethodCatalog(BeanResolver.empty());

        List<String> names = catalog.instanceMethodsOf("java.util.List").stream()
                .map(MethodMetadata::name)
                .toList();

        assertTrue(names.contains("forEach"));
        assertTrue(names.contains("stream"));
        assertTrue(names.contains("get"));
        // 静态方法不属于实例那一侧，混进来会让 `list.randomUUID()` 变成可选项
        assertFalse(names.contains("of"));
        // 类型参数名要给出，否则 List<Foo>.get(int) 的返回类型 E 没法还原成 Foo
        assertEquals(List.of("E"), catalog.typeVarsOf("java.util.List"));
    }

    /** 枚举常量不是方法，但它是 valueOf 唯一接受的写法，必须能列出来。 */
    @Test
    void listsEnumConstants() {
        MethodCatalog catalog = new MethodCatalog(BeanResolver.empty());

        assertEquals(List.of("A", "B"), catalog.constantsOf(TwoConstants.class.getName()));
        // 非枚举没有常量，普通类也不会被当成枚举
        assertTrue(catalog.constantsOf("java.util.UUID").isEmpty());
        assertTrue(catalog.constantsOf("nope.Nope").isEmpty());
    }

    /** 非法的类型名不能进缓存，也不能进 forName。 */
    @Test
    void illegalTypeNameYieldsNothingForEveryView() {
        MethodCatalog catalog = new MethodCatalog(BeanResolver.empty());

        assertTrue(catalog.instanceMethodsOf("[Ljava.lang.String;").isEmpty());
        assertTrue(catalog.constantsOf("java/util/UUID").isEmpty());
        assertTrue(catalog.typeVarsOf("").isEmpty());
        assertTrue(catalog.typeVarsOf(null).isEmpty());
    }

    @Test
    void illegalTypeNameYieldsEmptyList() {
        MethodCatalog catalog = new MethodCatalog(BeanResolver.empty());

        // 数组描述符是 Class.forName 认识的写法，但不是类名
        assertTrue(catalog.staticMethods("[Ljava.lang.String;").isEmpty());
        assertTrue(catalog.staticMethods("java/util/UUID").isEmpty());
        assertTrue(catalog.staticMethods("").isEmpty());
        assertTrue(catalog.staticMethods(null).isEmpty());
    }

    /**
     * 整个能力的地基：读方法签名不能执行目标类的静态初始化块。
     *
     * <p>如果这条破了，「打开编辑器敲个点号」就等于「跑一遍别人写在 static 里的连库、
     * 抢锁、注册钩子」—— 补全这个功能就不该存在。实现上靠
     * {@code Class.forName(name, false, loader)}，这里锁死它。
     */
    @Test
    void readingStaticMethodsDoesNotRunStaticInitializer() {
        MethodCatalog catalog = new MethodCatalog(BeanResolver.empty());

        List<String> names = catalog.staticMethods(HasStaticInit.class.getName()).stream()
                .map(MethodMetadata::name)
                .toList();

        assertTrue(names.contains("hello"));
        assertEquals("none", Marker.initializedBy);
    }

    /**
     * 没写 import 的简名到底绑到哪个类 —— 这是「补全给出的方法能不能真的调到」的分界。
     *
     * <p>真实事故：{@code Category.allViews()} 里那个 {@code Category} 绑的是
     * {@code groovy.lang.Category}，业务枚举里同名的方法一个都调不到，
     * 而补全照样把它们列了出来。
     */
    @Test
    void defaultImportOfShadowsBusinessSimpleNames() {
        MethodCatalog catalog = new MethodCatalog(BeanResolver.empty());

        assertEquals("java.lang.String", catalog.defaultImportOf("String"));
        assertEquals("groovy.lang.Category", catalog.defaultImportOf("Category"));
        // 业务类不在默认导入包里：这个名字不改 import 就根本解析不出来
        assertNull(catalog.defaultImportOf("OrderService"));
        // 只接受简名，全限定名和数组描述符一律不认
        assertNull(catalog.defaultImportOf("java.lang.String"));
        assertNull(catalog.defaultImportOf("[Ljava.lang.String;"));
        assertNull(catalog.defaultImportOf(null));
    }
}
