package com.harlanking.harken.core.spi;

import com.harlanking.harken.core.api.ScriptRequest;

/**
 * 执行前的静态校验。返回非 {@code null} 即拒绝执行。
 *
 * <p><b>这不是安全边界，是「减少误伤面积」的护栏。</b> Groovy 没有可信沙箱（ADR-07），
 * 所有基于文本的规则都能被反射绕过。它的价值是拦住手滑和 AI 的自动生成物，
 * 不是拦住攻击者。真正的兜底是事务回滚 + 审计 + 网络隔离。
 *
 * <p>内置实现在 {@link com.harlanking.harken.core.impl.DefaultScriptValidator}：
 * 禁编译期执行特性（{@code @Grab} / {@code @ASTTest} / {@code extensions}），
 * 只读执行时禁文件、进程、外发消息类 API。
 *
 * <p>抛异常会中断执行。
 *
 * @author harlan
 * @since 1.0.0
 */
public interface ScriptValidator {

    /**
     * 校验请求。
     *
     * @param request 待校验的请求，包含通道与脚本
     * @return 通过返回 {@code null}，拒绝返回错误描述
     */
    ValidationError validate(ScriptRequest request);

    /**
     * 排序值，数字小的先执行。
     *
     * @return 顺序值，默认 {@code 0}
     */
    default int getOrder() {
        return 0;
    }

    /**
     * 校验失败信息。
     *
     * @param code    机器可读的错误码，例如 {@code HARKEN_FORBIDDEN_API}
     * @param message 给人看的说明
     * @param line    行号，未知填 {@code -1}
     * @author harlan
     * @since 1.0.0
     */
    record ValidationError(String code, String message, int line) {

        /**
         * 便捷构造：不关心行号。
         *
         * @param code    错误码
         * @param message 说明
         * @return 行号为 {@code -1} 的错误
         */
        public static ValidationError of(String code, String message) {
            return new ValidationError(code, message, -1);
        }
    }
}
