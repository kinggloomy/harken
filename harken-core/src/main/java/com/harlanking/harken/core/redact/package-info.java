/**
 * 审计与错误出口的敏感信息脱敏。
 *
 * <p>这里只有一个类 {@link com.harlanking.harken.core.redact.Redactor}，
 * 因为「哪些算敏感」必须是全局唯一的一份定义 —— 散成多个类之后，
 * 审计路径和错误路径的掩码规则就会漂移，最终谁也不知道哪条路径更严。
 *
 * <p>它不是安全边界，只是降低泄漏面。这个定位写在 {@code SECURITY.md} 里，不许含糊。
 *
 * @author harlan
 * @since 1.0.0
 */
package com.harlanking.harken.core.redact;
