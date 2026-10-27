package com.harlanking.harken.core.impl;

import com.harlanking.harken.core.api.ReadOnlyRequest;
import com.harlanking.harken.core.api.ReadWriteRequest;
import com.harlanking.harken.core.api.ScriptRequest;
import com.harlanking.harken.core.spi.ScriptValidator;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置校验规则。两层，性质完全不同，别混。
 *
 * <h2>第一层：编译期执行特性（对所有人，含人类）</h2>
 * 不是为了防恶意，是为了<b>防自伤</b>。Groovy 的 {@code @Grab} 会在编译阶段真的去下载 jar，
 * {@code @Builder(builderStrategy)} 这类 AST 注解在执行之前就跑了任意代码 ——
 * Jenkins 那批 CVE 全是这个模式（ADR-18）。用户从网上抄一段脚本，里面带着这些注解，
 * 后果是他自己想不到的。
 *
 * <h2>第二层：副作用 API（只在只读执行时）</h2>
 * 拦文件、进程、HTTP/MQ 客户端。这些不在数据库事务里，回滚兜不住。
 * <b>这一层能被反射绕过，它是告示牌不是门锁。</b> 真正的兜底是事务回滚。
 *
 * <p>本类永不返回 {@code null} 之外的「警告」结果：要么通过，要么拒绝。
 * 将来如果需要「警告但放行」，应新增服务端提示机制，不要在这里返回特殊值。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class DefaultScriptValidator implements ScriptValidator {

    private record Rule(String code, Pattern pattern, String message) {
    }

    private static final List<Rule> COMPILE_TIME = List.of(
            new Rule("HARKEN_COMPILE_TIME_EVAL",
                    Pattern.compile("@(?:Grab|GrabConfig|GrabResolver|ASTTest|AnnotationCollector)\\b"),
                    "禁止使用 @Grab / @ASTTest 等编译期执行特性：它们会在编译阶段就执行代码或下载依赖"),
            new Rule("HARKEN_COMPILE_TIME_EVAL",
                    Pattern.compile("@(?:Builder|CompileStatic|TypeChecked)\\s*\\([^)]*(?:builderStrategy|extensions)\\s*="),
                    "禁止在 AST 注解里使用 extensions / builderStrategy 参数：编译期会执行任意代码")
    );

    private static final List<Rule> SIDE_EFFECT = List.of(
            new Rule("HARKEN_FORBIDDEN_API",
                    Pattern.compile("\\bRuntime\\s*\\.\\s*getRuntime\\b|\\bnew\\s+ProcessBuilder\\b|\\bSystem\\s*\\.\\s*exit\\b"),
                    "只读执行禁止进程操作（Runtime / ProcessBuilder / System.exit）"),
            new Rule("HARKEN_FORBIDDEN_API",
                    Pattern.compile("\\bFiles\\s*\\.\\s*(?:delete|deleteIfExists|write|move|copy)\\b|\\bnew\\s+FileOutputStream\\b"),
                    "只读执行禁止写文件（Files.* / FileOutputStream）"),
            new Rule("HARKEN_FORBIDDEN_API",
                    Pattern.compile("\\b(?:kafkaTemplate|rabbitTemplate|restTemplate|webClient|jmsTemplate)\\s*\\.\\s*(?:send|convertAndSend|postFor|exchange|get\\s*\\()"),
                    "只读执行禁止对外发送消息或发起 HTTP 调用：这些副作用不在事务里，回滚兜不住")
    );

    /**
     * 本实现无状态，可安全并发使用。
     */
    public DefaultScriptValidator() {
    }

    /**
     * 按「编译期特性 → 只读副作用」两轮检查。
     *
     * @param request 待校验请求
     * @return 命中第一条规则即返回；全部通过返回 {@code null}
     */
    @Override
    public ValidationError validate(ScriptRequest request) {
        String script = request.script();

        ValidationError hit = firstHit(script, COMPILE_TIME);
        if (hit != null) {
            return hit;
        }
        if (isReadOnly(request)) {
            return firstHit(script, SIDE_EFFECT);
        }
        return null;
    }

    private static boolean isReadOnly(ScriptRequest request) {
        if (request instanceof ReadOnlyRequest) {
            return true;
        }
        if (request instanceof ReadWriteRequest rw) {
            return !rw.commit();
        }
        return true;
    }

    private static ValidationError firstHit(String script, List<Rule> rules) {
        for (Rule rule : rules) {
            Matcher m = rule.pattern().matcher(script);
            if (m.find()) {
                return new ValidationError(rule.code(), rule.message(), lineOf(script, m.start()));
            }
        }
        return null;
    }

    private static int lineOf(String script, int index) {
        int line = 1;
        int limit = Math.min(index, script.length());
        for (int i = 0; i < limit; i++) {
            if (script.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
