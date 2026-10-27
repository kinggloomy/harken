package com.harlanking.harken.core;

/**
 * Harken 的异常族。用嵌套静态类而不是散落的一堆文件。
 *
 * <p>统一继承 {@link RuntimeException}：脚本执行的调用方（HTTP 层、AI 通道）都是
 * 「一次请求一次执行」，checked exception 只会让签名变脏，不会带来更好的错误处理。
 * 真正需要分类处理的信息放在 {@code code} / {@code errorType} 字段里。
 *
 * <p>注意：业务异常一般<b>不会</b>以抛出形式离开 {@link HarkenService}，
 * 而是被包装进 {@link com.harlanking.harken.core.api.ScriptResult}，
 * 以便把「执行失败」和「传输失败」区分开。本类主要在引擎、校验、事务这些内层抛出。
 *
 * @author harlan
 * @since 1.0.0
 */
public class HarkenException extends RuntimeException {

    /**
     * @param message 错误描述
     */
    public HarkenException(String message) {
        super(message);
    }

    /**
     * @param message 错误描述
     * @param cause   原始异常
     */
    public HarkenException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 执行超时。
     *
     * <p>语义是「尽力而为」：能响应中断的纯计算死循环会被 {@code @ThreadInterrupt}
     * 打断退出；卡在不可中断的阻塞调用（socket read、{@code synchronized} 死锁、native 方法）
     * 里的线程只能被丢弃，成为僵尸线程（ADR-08）。所以超时只能保证「调用方不再等待」，
     * 不能保证「脚本已经停止」。
     */
    public static class Timeout extends HarkenException {
        private final long timeoutMs;

        /**
         * @param timeoutMs 被超过的超时阈值
         */
        public Timeout(long timeoutMs) {
            super("脚本执行超过 " + timeoutMs + "ms 未返回，已中断");
            this.timeoutMs = timeoutMs;
        }

        /**
         * @return 超时阈值（毫秒）
         */
        public long timeoutMs() {
            return timeoutMs;
        }
    }

    /**
     * 校验不通过（危险 API、编译期执行注解、超长脚本等）。
     *
     * <p>{@code code} 会被透传到 {@code ScriptResult.errorType} 与审计记录，
     * 前端据此决定提示文案，AI 据此自我修正。
     */
    public static class Rejected extends HarkenException {
        private final String code;
        private final int line;

        /**
         * @param code    机器可读错误码，例如 {@code HARKEN_NO_TRANSACTION}
         * @param message 给人看的说明
         * @param line    出错行号，未知填 {@code -1}
         */
        public Rejected(String code, String message, int line) {
            super(message);
            this.code = code;
            this.line = line;
        }

        /**
         * @return 机器可读错误码
         */
        public String code() {
            return code;
        }

        /**
         * @return 出错行号，未知为 {@code -1}
         */
        public int line() {
            return line;
        }
    }

    /**
     * 编译失败，带上行列号，前端可以据此画语法红线。
     */
    public static class Compilation extends HarkenException {
        private final int line;
        private final int column;

        /**
         * @param message 原始编译错误
         * @param line    行号，未知填 {@code -1}
         * @param column  列号，未知填 {@code -1}
         * @param cause   Groovy 抛出的原始异常
         */
        public Compilation(String message, int line, int column, Throwable cause) {
            super(message, cause);
            this.line = line;
            this.column = column;
        }

        /**
         * @return 行号，未知为 {@code -1}
         */
        public int line() {
            return line;
        }

        /**
         * @return 列号，未知为 {@code -1}
         */
        public int column() {
            return column;
        }
    }
}
