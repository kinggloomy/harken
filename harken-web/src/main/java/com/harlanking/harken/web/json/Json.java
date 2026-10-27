package com.harlanking.harken.web.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个刚好够用的 JSON 编解码器。
 *
 * <h2>为什么手写而不是引 Jackson</h2>
 * <p>Harken 的卖点之一是「零外部依赖」。为了收发四个字段（{@code script}、{@code commit}、
 * {@code username}、{@code password}）就往使用者的 classpath 里塞一个 Jackson，
 * 与这个卖点直接冲突；而 Jackson 的版本冲突恰恰是 Spring Boot 应用最常见的运行时故障来源。
 *
 * <p>另一个原因是边界：这个编解码器只处理 Harken 自己的协议。
 * 它<b>不</b>是一个通用 JSON 库 —— 没有 {@code @JsonView}、没有类型推断、
 * 数字一律 {@link Long} 或 {@link Double}，不做 BigDecimal。
 * 输入被严格校验，不认识的东西直接报错，而不是猜。
 *
 * <h2>安全约束</h2>
 * <ul>
 *   <li>最大嵌套深度 {@value #MAX_DEPTH}：防「一万个左方括号」把调用栈打穿。</li>
 *   <li>拒绝尾部多余内容：{@code {"a":1} {"b":2}} 必须报错，不能只解析第一个。</li>
 *   <li>字符串里裸控制字符（{@code < 0x20}）报错：防止用换行拆开日志、伪造审计行。</li>
 * </ul>
 *
 * <h2>对象模型</h2>
 * <p>解析结果是 {@code Map<String,Object>} / {@code List<Object>} / {@code String} /
 * {@code Boolean} / {@code Long} / {@code Double} / {@code null}。
 * 明确、可预测，没有「为什么这里是个 LinkedHashMap 子类」的意外。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class Json {

    /** 最大嵌套层数。正常请求不会超过 4 层。 */
    public static final int MAX_DEPTH = 32;

    private Json() {
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析一段 JSON 文本。
     *
     * @param text JSON 文本，不能为 {@code null}
     * @return {@code Map}/{@code List}/{@code String}/{@code Boolean}/{@code Long}/{@code Double}/{@code null}
     * @throws JsonException 语法错误、深度超限、尾部有多余内容
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("JSON 文本为 null");
        }
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.readValue(0);
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new JsonException("JSON 结束后还有多余内容，位置 " + p.pos);
        }
        return value;
    }

    /**
     * 解析并要求顶层是对象。
     *
     * @param text JSON 文本
     * @return 顶层对象，永不为 {@code null}
     * @throws JsonException 语法错误或顶层不是对象
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("请求体必须是 JSON 对象，实际是 " + typeName(value));
        }
        return (Map<String, Object>) value;
    }

    private static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map) {
            return "object";
        }
        if (value instanceof List) {
            return "array";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        return "number";
    }

    private static final class Parser {

        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonException("JSON 嵌套超过 " + MAX_DEPTH + " 层，已拒绝解析");
            }
            if (atEnd()) {
                throw new JsonException("JSON 意外结束（期望一个值）");
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return readObject(depth);
                case '[':
                    return readArray(depth);
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw new JsonException("无法识别的字符 '" + c + "'，位置 " + pos);
            }
        }

        private void expect(String literal) {
            if (!src.startsWith(literal, pos)) {
                throw new JsonException("位置 " + pos + " 期望 " + literal);
            }
            pos += literal.length();
        }

        private Map<String, Object> readObject(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (!atEnd() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || src.charAt(pos) != '"') {
                    throw new JsonException("对象的键必须是字符串，位置 " + pos);
                }
                String key = readString();
                skipWhitespace();
                if (atEnd() || src.charAt(pos) != ':') {
                    throw new JsonException("键 " + key + " 后缺少冒号，位置 " + pos);
                }
                pos++;
                skipWhitespace();
                map.put(key, readValue(depth + 1));
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("对象未闭合");
                }
                char c = src.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw new JsonException("对象里期望 ',' 或 '}'，位置 " + pos);
                }
            }
        }

        private List<Object> readArray(int depth) {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (!atEnd() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue(depth + 1));
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("数组未闭合");
                }
                char c = src.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new JsonException("数组里期望 ',' 或 ']'，位置 " + pos);
                }
            }
        }

        private String readString() {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("字符串未闭合");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw new JsonException("转义符后意外结束");
                    }
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append(readHex4());
                        default -> throw new JsonException("不支持的转义 \\" + e + "，位置 " + pos);
                    }
                } else if (c < 0x20) {
                    // 裸控制字符：JSON 规范禁止，且能被用来在日志里伪造换行
                    throw new JsonException(String.format(
                            "字符串里出现未转义的控制字符 U+%04X，位置 %d", (int) c, pos - 1));
                } else {
                    sb.append(c);
                }
            }
        }

        private char readHex4() {
            if (pos + 4 > src.length()) {
                throw new JsonException("\\u 转义不足 4 位，位置 " + pos);
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(src.charAt(pos + i), 16);
                if (digit < 0) {
                    throw new JsonException("\\u 转义里有非十六进制字符，位置 " + (pos + i));
                }
                value = (value << 4) | digit;
            }
            pos += 4;
            return (char) value;
        }

        private Object readNumber() {
            int start = pos;
            if (!atEnd() && src.charAt(pos) == '-') {
                pos++;
            }
            // 整数部分：0 或 [1-9][0-9]*
            if (atEnd()) {
                throw new JsonException("数字不完整，位置 " + start);
            }
            if (src.charAt(pos) == '0') {
                pos++;
            } else if (src.charAt(pos) >= '1' && src.charAt(pos) <= '9') {
                while (!atEnd() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            } else {
                throw new JsonException("数字格式错误，位置 " + start);
            }
            boolean fractional = false;
            if (!atEnd() && src.charAt(pos) == '.') {
                fractional = true;
                pos++;
                int digits = 0;
                while (!atEnd() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                    digits++;
                }
                if (digits == 0) {
                    throw new JsonException("小数点后缺少数字，位置 " + start);
                }
            }
            if (!atEnd() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                fractional = true;
                pos++;
                if (!atEnd() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                int digits = 0;
                while (!atEnd() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                    digits++;
                }
                if (digits == 0) {
                    throw new JsonException("指数部分缺少数字，位置 " + start);
                }
            }
            String raw = src.substring(start, pos);
            try {
                return fractional ? (Object) Double.valueOf(raw) : (Object) Long.valueOf(raw);
            } catch (NumberFormatException e) {
                throw new JsonException("数字超出范围：" + raw);
            }
        }
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    /**
     * 把对象写成 JSON 文本。
     *
     * <p>支持 {@code Map}、{@code Iterable}、数组、{@code CharSequence}、{@code Boolean}、
     * {@code Number}、{@code null}；其他类型按 {@code toString()} 当字符串写出。
     * 这个兜底是刻意的：返回值可能是任意脚本对象，把它整段写成字符串
     * 也好过序列化失败让整个响应 500。
     *
     * @param value 待序列化对象，可为 {@code null}
     * @return JSON 文本
     */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            sb.append("\"<嵌套过深，已截断>\"");
            return;
        }
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map, depth);
        } else if (value instanceof Iterable<?> it) {
            writeArray(sb, it, depth);
        } else if (value instanceof Object[] array) {
            writeArray(sb, List.of(array), depth);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            writeNumber(sb, n);
        } else if (value instanceof CharSequence cs) {
            writeString(sb, cs.toString());
        } else if (value instanceof Character c) {
            writeString(sb, String.valueOf(c));
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        if (n instanceof Double d && (d.isNaN() || d.isInfinite())) {
            // JSON 没有 NaN/Infinity，写成字符串，避免吐出非法 JSON
            writeString(sb, d.toString());
            return;
        }
        if (n instanceof Float f && (f.isNaN() || f.isInfinite())) {
            writeString(sb, f.toString());
            return;
        }
        sb.append(n);
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, int depth) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValue(sb, e.getValue(), depth + 1);
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> items, int depth) {
        sb.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, item, depth + 1);
        }
        sb.append(']');
    }

    /**
     * 写字符串并转义。
     *
     * <p>{@code < 0x20} 的控制字符一律走 {@code \\u00XX}，
     * 不做「只转义换行」的省事写法 —— 少转义一个字符就够伪造审计日志了。
     */
    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
