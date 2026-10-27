package com.harlanking.harken.web.servlet;

import com.harlanking.harken.core.api.ScriptResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ScriptResult} 的 JSON 形状。
 *
 * <p>两个通道（人类 / AI）<b>共用</b>这个形状，因为它们描述的是同一件事：
 * 一段脚本在这台机器上跑出了什么。通道差异体现在 HTTP 端点与凭据上，不体现在响应体上 ——
 * 让 AI 客户端学两套响应格式是纯粹的成本。
 *
 * <h2>字段全部保留，包括 null</h2>
 * <p>省掉 null 字段能省几个字节，但会让客户端无法区分
 * 「这个字段不存在」和「这个字段是 null」——在跨版本兼容时这是致命的。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class ScriptResultJson {

    private ScriptResultJson() {
    }

    /**
     * 转换。
     *
     * @param result 执行结果
     * @return 可直接序列化的 Map
     */
    public static Map<String, Object> toMap(ScriptResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", result.ok());
        m.put("result", result.result());
        m.put("output", result.output());
        m.put("error", result.error());
        m.put("errorType", result.errorType());
        m.put("durationMs", result.durationMs());
        m.put("rolledBack", result.rolledBack());
        m.put("truncated", result.truncated());
        m.put("hint", result.hint());
        m.put("channel", result.channel());
        m.put("traceId", result.traceId());
        m.put("affectedRows", result.affectedRows());
        return m;
    }
}
