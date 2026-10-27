package com.harlanking.harken.core;

import com.harlanking.harken.core.metadata.ClassIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类名索引的行为约定：import 补全列出来的东西必须能真的写进 import 语句。
 */
class ClassIndexTest {

    @Test
    void groupsClassesByPackage() {
        ClassIndex index = ClassIndex.of(List.of(
                "com.demo.util.CalcUtil",
                "com.demo.util.Money",
                "com.demo.order.OrderService"));

        assertEquals(Map.of(
                "com.demo.order", List.of("OrderService"),
                "com.demo.util", List.of("CalcUtil", "Money")), index.byPackage());
        assertEquals(3, index.size());
    }

    @Test
    void fqcnOfResolvesUniqueNamesOnly() {
        ClassIndex index = ClassIndex.of(List.of(
                "com.demo.util.CalcUtil",
                "com.demo.order.CalcUtil",
                "com.demo.order.OrderService"));

        // 重名时必须拒绝回答：猜错的后果是补全给出另一个类的方法，脚本一跑就报错
        assertNull(index.fqcnOf("CalcUtil"));
        assertEquals("com.demo.order.OrderService", index.fqcnOf("OrderService"));
        assertNull(index.fqcnOf("Nope"));
        assertNull(ClassIndex.empty().fqcnOf("OrderService"));
    }

    @Test
    void skipsNestedAndAnonymousClasses() {
        ClassIndex index = ClassIndex.of(List.of(
                "com.demo.Outer",
                "com.demo.Outer$Inner",
                "com.demo.Outer$1"));

        // 内部类写不进 import，列出来只会让人选中后报错
        assertEquals(List.of("Outer"), index.byPackage().get("com.demo"));
    }

    @Test
    void ignoresMalformedNames() {
        ClassIndex index = ClassIndex.of(List.of("", "NoPackage", "com.demo.", "  "));

        assertTrue(index.byPackage().isEmpty());
        assertEquals(0, index.size());
    }

    @Test
    void emptyIndexIsUsable() {
        assertTrue(ClassIndex.empty().byPackage().isEmpty());
        assertEquals(0, ClassIndex.empty().size());
        // 没有业务类 ≠ 什么都查不到：JDK 类来自 jrt，跟 classpath 无关。
        // 非 Spring 场景靠这条还能提示 import java.util.ArrayList
        assertTrue(ClassIndex.empty().jdkClassesIn("no.such.package").isEmpty());
    }

    @Test
    void readsJdkPackageFromJrt() {
        ClassIndex index = ClassIndex.empty();

        List<String> util = index.jdkClassesIn("java.util");

        // classpath* 永远拿不到 JDK 类（实测双形态双 JDK 都是 0），只能走 jrt
        assertTrue(util.contains("ArrayList"), "读不到 java.util：" + util.size());
        assertTrue(util.contains("HashMap"));
        assertFalse(util.contains("Map$Entry"), "内部类不该出现在候选里");
    }

    @Test
    void rejectsIllegalJdkPackageName() {
        ClassIndex index = ClassIndex.empty();

        // 包名会拼进 jrt 路径，必须先拦
        assertTrue(index.jdkClassesIn("java/util").isEmpty());
        assertTrue(index.jdkClassesIn("../etc").isEmpty());
        assertTrue(index.jdkClassesIn("no.such.package").isEmpty());
        assertTrue(index.jdkClassesIn(null).isEmpty());
    }
}
