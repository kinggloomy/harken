package com.harlanking.harken.web.json;

/**
 * 极简 JSON 解析失败异常。
 *
 * <p>刻意不用受检异常：HTTP 层对 JSON 错误的处理永远是「400 + 一句人话」，
 * 不需要在每一层都声明它。
 *
 * @author harlan
 * @since 1.0.0
 */
public class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 面向使用者的错误描述，含字符偏移
     */
    public JsonException(String message) {
        super(message);
    }
}
