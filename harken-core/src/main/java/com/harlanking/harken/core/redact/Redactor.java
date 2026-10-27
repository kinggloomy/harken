package com.harlanking.harken.core.redact;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 敏感信息脱敏：把「看起来是密钥」的片段替换成 {@value #MASK}。
 *
 * <h2>为什么需要它</h2>
 * <p>审计要存脚本原文，否则「谁在什么时候把 3 万条订单标记成已支付」这件事就查不清。
 * 但脚本原文里可能写着 {@code password = "123456"}、异常消息里可能带着
 * {@code jdbc:mysql://root:P@ssw0rd@10.0.0.5:3306/db}。审计库往往比业务库更容易被翻看
 * （DBA、日志平台、备份文件），把凭据抄一份进去等于自己制造泄漏面。
 *
 * <h2>它<b>不是</b>什么</h2>
 * <p><b>这不是加密，也不是万无一失的防泄漏。</b> 它只认「键名 + 赋值」这种最典型的形态，
 * 靠正则，因此注定有漏：
 * <ul>
 *   <li>{@code def p = "s3cret"; connect(user, p)} —— 变量名不叫 password，认不出；</li>
 *   <li>{@code new String(new byte[]{...})} 拼出来的凭据，认不出；</li>
 *   <li>图片/二进制里夹带的内容，认不出。</li>
 * </ul>
 * <p>它的定位是<b>降低日常泄漏面</b>（把「顺手抄进审计」这件事挡住），不是边界防护。
 * 真正的边界是：不要让脚本接触凭据、把审计存储当敏感系统对待。这条要原样写进 {@code SECURITY.md}。
 *
 * <h2>设计取舍</h2>
 * <ul>
 *   <li><b>保留键名，只掩值。</b> {@code password="***"} 比整段删掉有用得多 ——
 *       看审计的人需要知道「这里本来有个密码」，而不是面对一段凭空消失的代码。</li>
 *   <li><b>幂等。</b> 已经掩过的文本再掩一次结果不变（{@code ***} 会再次匹配、再次替换成 {@code ***}），
 *       所以多层调用不需要小心翼翼。</li>
 *   <li><b>不掩返回值。</b> 返回值是业务数据本身，掩掉等于篡改数据（AI 读到 {@code token=***}
 *       会做出错误判断）。审计只存返回值摘要，不存正文，见 {@code AuditRecord#resultDigest}。</li>
 * </ul>
 *
 * @author harlan
 * @since 1.0.0
 */
public final class Redactor {

    /** 掩码串。写成常量是为了让「审计里看到三个星号」这件事有唯一解释。 */
    public static final String MASK = "***";

    /**
     * 一条规则：匹配什么、换成什么。
     *
     * @param pattern     正则
     * @param replacement 替换串，支持 {@code $1} 这类分组引用
     */
    private record Rule(Pattern pattern, String replacement) {
    }

    /**
     * 敏感键名。放一起是为了让「哪些算敏感」只有一处定义。
     *
     * <p>故意<b>不</b>包含 {@code hash}、{@code salt} 这类词：它们在审计里通常是
     * 「密码哈希算法的参数」，本身就是不该被误伤的正常信息。
     */
    private static final String KEYS = "password|passwd|pwd|secret|client[_-]?secret"
            + "|secret[_-]?key|access[_-]?key|api[_-]?key|private[_-]?key|credential"
            + "|token|auth[_-]?token";

    /**
     * 规则表，按顺序应用。顺序有讲究：
     * <ol>
     *   <li>先处理「带引号的值」——最明确，也是脚本里最常见的写法；</li>
     *   <li>再处理「不带引号的值」——兜住 {@code password = System.getenv("X")} 这类；</li>
     *   <li>然后是 URL 里的 userinfo（{@code ://user:pass@host}）；</li>
     *   <li>最后是 {@code Bearer}/{@code Basic} 与 JWT 字面量 —— 它们没有键名，只能按形态认。</li>
     * </ol>
     */
    private static final List<Rule> RULES = List.of(
            // password = "xxx" / password: 'xxx' / PASSWORD="xxx" / JSON 的 "password":"xxx"
            // 分隔符里允许一个可选的收尾引号，否则 JSON 形态（键被引号包着）完全匹配不到
            new Rule(Pattern.compile(
                    "(?i)\\b(" + KEYS + ")\\b(\"?[ \\t]*[:=][ \\t]*)([\"'])([^\"'\\r\\n]{1,4096})\\3"),
                    "$1$2$3" + MASK + "$3"),
            // password = abc123（不带引号的**字面量**）。
            // 刻意不匹配 password = System.getenv("X") 这种：右边根本没有密钥字面量，
            // 掩掉只会留下 "password = ***(...)" 这种残句，反而让审计更难读。
            // 末尾的 lookahead 要求值后面紧跟分隔符或行尾，杜绝「吃掉半个表达式」。
            new Rule(Pattern.compile(
                    "(?i)\\b(" + KEYS + ")\\b([ \\t]*[:=][ \\t]*)([^\\s,;)\\]}\"'|(]{3,})\\s*(?=$|[;,)}\\n])"),
                    "$1$2" + MASK),
            // jdbc:mysql://root:P@ssw0rd@host:3306/db —— 只掩冒号到最后一个 @ 之间那段。
            // 密码里带 @ 是真实存在的（未转义的连接串），所以第二组必须贪婪到最后一个 @，
            // 否则 P@ssw0rd 会被切成 P / ssw0rd@，留下半截密码在审计里。
            new Rule(Pattern.compile(
                    "(://[^:/@\\s]{1,64}:)([^\\s/]*)(@)"),
                    "$1" + MASK + "$3"),
            // Authorization: Bearer abc.def
            new Rule(Pattern.compile("(?i)\\b(bearer\\s+)([A-Za-z0-9._~+/=-]{8,})"),
                    "$1" + MASK),
            // Authorization: Basic YWRtaW46cHc=
            new Rule(Pattern.compile("(?i)\\b(basic\\s+)([A-Za-z0-9+/=]{8,})"),
                    "$1" + MASK),
            // 裸 JWT：三段 base64url，header 段必然以 eyJ 开头
            new Rule(Pattern.compile(
                    "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
                    MASK)
    );

    private Redactor() {
    }

    /**
     * 脱敏。{@code null} 原样返回（审计里很多字段允许为空）。
     *
     * @param text 原文
     * @return 脱敏后的文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 快速短路：绝大多数脚本里没有任何敏感键名，没必要跑 6 条正则
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (!mightContainSecret(lower)) {
            return text;
        }
        String out = text;
        for (Rule rule : RULES) {
            out = rule.pattern().matcher(out).replaceAll(rule.replacement());
        }
        return out;
    }

    /**
     * 便宜的前置判断：连关键词都搜不到，就不可能命中任何一条规则。
     *
     * <p>为什么值得做：审计在每次执行时都会跑，脚本 10KB 时 6 条正则的扫描成本不小，
     * 而真实脚本里出现 {@code password=} 的比例极低。这个短路把常态成本压到一次子串扫描。
     */
    private static boolean mightContainSecret(String lower) {
        return lower.contains("password") || lower.contains("passwd") || lower.contains("pwd")
                || lower.contains("secret") || lower.contains("token") || lower.contains("access")
                || lower.contains("api_key") || lower.contains("api-key") || lower.contains("apikey")
                || lower.contains("credential") || lower.contains("bearer") || lower.contains("basic ")
                || lower.contains("://") || lower.contains("eyj");
    }
}
