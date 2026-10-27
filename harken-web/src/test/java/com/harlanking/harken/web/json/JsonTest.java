package com.harlanking.harken.web.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Json} 的行为约定。
 *
 * <p>这个类的每个测试都在钉一条「不能松动」的边界：如果哪天有人为了「宽容一点」
 * 放松了尾部垃圾检查或者深度限制，测试会立刻红。
 */
class JsonTest {

    @Test
    @DisplayName("解析对象：字符串、布尔、null、整数、小数都在")
    @SuppressWarnings("unchecked")
    void parsesObject() {
        Map<String, Object> m = Json.parseObject(
                "{\"script\":\"return 1+1\",\"commit\":false,\"n\":42,\"d\":1.5,\"z\":null}");

        assertEquals("return 1+1", m.get("script"));
        assertEquals(Boolean.FALSE, m.get("commit"));
        assertEquals(42L, m.get("n"));
        assertEquals(1.5d, m.get("d"));
        assertNull(m.get("z"));
        assertTrue(m.containsKey("z"), "显式 null 必须保留键，不能与「键不存在」混淆");
    }

    @Test
    @DisplayName("嵌套数组与对象")
    @SuppressWarnings("unchecked")
    void parsesNested() {
        Map<String, Object> m = Json.parseObject("{\"a\":[1,{\"b\":[true,null]}]}");
        List<Object> a = (List<Object>) m.get("a");
        assertEquals(1L, a.get(0));
        Map<String, Object> inner = (Map<String, Object>) a.get(1);
        List<Object> b = (List<Object>) inner.get("b");
        assertEquals(Boolean.TRUE, b.get(0));
        assertNull(b.get(1));
    }

    @Test
    @DisplayName("\\u 转义与中文原样输入等价")
    void parsesUnicodeEscapes() {
        assertEquals("中文", Json.parse("\"\\u4e2d\\u6587\""));
        assertEquals("中文", Json.parse("\"中文\""));
        assertEquals("a\"b", Json.parse("\"a\\\"b\""));
        assertEquals("a\nb", Json.parse("\"a\\nb\""));
    }

    @Test
    @DisplayName("尾部有多余内容必须报错")
    void rejectsTrailingGarbage() {
        JsonException e = assertThrows(JsonException.class, () -> Json.parse("{\"a\":1} {\"b\":2}"));
        assertTrue(e.getMessage().contains("多余"), e.getMessage());
    }

    @Test
    @DisplayName("字符串里裸控制字符必须报错，否则可以伪造日志行")
    void rejectsRawControlCharacter() {
        assertThrows(JsonException.class, () -> Json.parse("\"a\nb\""));
    }

    @Test
    @DisplayName("嵌套超过上限报错，而不是把栈打穿")
    void rejectsTooDeep() {
        String deep = "[".repeat(Json.MAX_DEPTH + 5) + "]".repeat(Json.MAX_DEPTH + 5);
        assertThrows(JsonException.class, () -> Json.parse(deep));
    }

    @Test
    @DisplayName("顶层不是对象时 parseObject 报错")
    void parseObjectRejectsArray() {
        assertThrows(JsonException.class, () -> Json.parseObject("[]"));
    }

    @Test
    @DisplayName("数字格式严格：前导零、孤立小数点都拒绝")
    void rejectsBadNumbers() {
        assertThrows(JsonException.class, () -> Json.parse("01"));
        assertThrows(JsonException.class, () -> Json.parse("1."));
        assertThrows(JsonException.class, () -> Json.parse("1e"));
    }

    @Test
    @DisplayName("序列化会转义引号、反斜杠、换行与控制字符")
    void writesEscapes() {
        String out = Json.write("a\"b\\c\nd\u0001");
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", out);
    }

    @Test
    @DisplayName("序列化再解析回到原值（含中文与 emoji）")
    void roundTrip() {
        String original = "select * from 订单 where 名称 = '测试😀'";
        String json = Json.write(Map.of("script", original));
        Map<String, Object> back = Json.parseObject(json);
        assertEquals(original, back.get("script"));
    }

    @Test
    @DisplayName("NaN 写成字符串，保证输出永远是合法 JSON")
    void writesNanAsString() {
        String out = Json.write(Double.NaN);
        assertEquals("\"NaN\"", out);
        assertFalse(out.startsWith("NaN"));
    }
}
