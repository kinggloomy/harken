package com.harlanking.harken.spring.boot;

import com.harlanking.harken.web.auth.SecretResolver;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.env.Environment;

/**
 * AI 通道是否应该被注册。
 *
 * <h2>为什么用 Condition 而不是「注册了但内部判断」</h2>
 * <p>「没配 Token → 端点存在但拒绝所有请求」和「没配 Token → 端点不存在」是两件事。
 * 后者不会给任何未来某次重构留下「忘了校验」的机会，因为压根没有可绕过的代码。
 * 条件装配是 Spring Boot 提供的、能在 bean 定义阶段就决定「存不存在」的机制。
 *
 * <h2>为什么直接读 Environment，而不是读 HarkenProperties</h2>
 * <p>条件评估发生在 bean 定义阶段，此时 {@code @ConfigurationProperties} 还没绑定完。
 * 直接读 Environment 是官方推荐做法，语义上也更贴近「配置有没有给出这个值」。
 *
 * <p>解析 {@code env:} 引用时也可能失败（环境变量缺失）。这里选择<b>不注册</b>：
 * 与运行时 {@code AuthService} 的降级方向保持一致 —— AI 通道要么带真 Token，
 * 要么不存在，没有中间态。
 *
 * @author harlan
 * @since 1.0.0
 */
final class AiChannelEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        if (!env.getProperty("harken.ai.enabled", Boolean.class, Boolean.TRUE)) {
            return false;
        }
        String raw = env.getProperty("harken.ai.token", "");
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            return !SecretResolver.resolve(raw).isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
