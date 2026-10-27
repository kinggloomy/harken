package com.harlanking.harken.jdbc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 轻量 SQL 分析器：只回答两个问题 —— <b>这是什么语句</b>、<b>提到了哪些标识符</b>。
 *
 * <h2>为什么不用 JSqlParser</h2>
 *
 * <p>计划书里原本写的是 JSqlParser，真到实现时放弃了。原因是这个项目的差异化卖点之一
 * 是「零外部依赖」——为了一个「防手滑」的功能引入一个几百 KB 的解析器，还要跟着
 * 数据库方言升级，收益与代价不成比例。代价是这个分析器<b>不完整</b>。
 *
 * <h2>它是保守的，但依然不是安全边界</h2>
 *
 * <p>做法是「宁可多拦，不可漏拦」：
 * <ol>
 *   <li>先剥掉注释与单引号字符串 —— 否则 {@code where note = 'supply_order'} 会误报；</li>
 *   <li>把剩下的文本按标识符切碎；</li>
 *   <li>黑名单拿<b>所有</b>标识符去比对，而不是只比对 {@code update} 后面那个表名。
 *       这样 {@code update a join b ...}、子查询、CTE 里的表名都跑不掉，
 *       代价是列名与表同名时会误报（可以接受：这是写操作，误报只会让人换个写法）。</li>
 * </ol>
 *
 * <p>明确的已知缺口（写进 SECURITY.md，不假装没有）：
 * <ul>
 *   <li>{@code CallableStatement} 调用存储过程 —— 里面写什么这里看不到；</li>
 *   <li>视图、触发器、外键级联删除；</li>
 *   <li>PostgreSQL 的 {@code $$ ... $$} 美元引用串（会被当成普通文本，可能误报）；</li>
 *   <li>数据库方言特有的语法（{@code UPSERT} 之外的各种 {@code ON CONFLICT} 变体）。</li>
 * </ul>
 *
 * <p>实例无状态、线程安全。解析结果按 SQL 文本缓存（见 {@link JdbcWriteGuard}），
 * 循环里执行同一条 SQL 不会反复解析。
 *
 * @author harlan
 * @since 1.0.0
 */
public final class SqlInspector {

    /**
     * 语句类别。
     *
     * @author harlan
     * @since 1.0.0
     */
    public enum Kind {

        /** 读：{@code select} / {@code show} / {@code explain}。黑名单不管读，管读的话工具就没用了。 */
        QUERY,

        /** 写数据：{@code insert} / {@code update} / {@code delete} / {@code merge} / {@code replace}。 */
        DML,

        /** 改结构：{@code create} / {@code alter} / {@code drop} / {@code truncate} / {@code rename}。 */
        DDL,

        /** 认不出来：存储过程调用、空串、方言语法等。不拦，也不计数。 */
        OTHER
    }

    /**
     * 一次分析的结果。
     *
     * @param kind        语句类别
     * @param verb        首关键字（小写），认不出时为空串
     * @param identifiers 语句里出现的全部标识符（小写、去引号、含 {@code schema.table} 的裸表名）
     * @author harlan
     * @since 1.0.0
     */
    public record Analysis(Kind kind, String verb, Set<String> identifiers) {

        /**
         * 这条语句是否会改动数据或结构。
         *
         * @return {@code true} 表示 DML 或 DDL
         */
        public boolean writes() {
            return kind == Kind.DML || kind == Kind.DDL;
        }
    }

    /** 语句类别为 {@code OTHER} 时的空结果，避免重复分配。 */
    private static final Analysis UNKNOWN = new Analysis(Kind.OTHER, "", Set.of());

    /**
     * 创建一个分析器。
     */
    public SqlInspector() {
        // 无状态
    }

    /**
     * 分析一条 SQL。
     *
     * @param sql SQL 文本，可为 {@code null}
     * @return 分析结果，永不为 {@code null}
     */
    public Analysis analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            return UNKNOWN;
        }
        List<String> tokens = tokenize(stripCommentsAndStrings(sql));
        if (tokens.isEmpty()) {
            return UNKNOWN;
        }
        String verb = tokens.get(0);
        Kind kind = kindOf(verb, tokens);
        return new Analysis(kind, verb, identifiersOf(tokens));
    }

    // ------------------------------------------------------------------

    private static Kind kindOf(String verb, List<String> tokens) {
        switch (verb) {
            case "select":
            case "show":
            case "explain":
            case "describe":
            case "desc":
            case "values":
                return Kind.QUERY;
            case "insert":
            case "update":
            case "delete":
            case "merge":
            case "replace":
            case "upsert":
                return Kind.DML;
            case "create":
            case "alter":
            case "drop":
            case "truncate":
            case "rename":
                return Kind.DDL;
            case "with":
                // CTE 的头部永远是 select，真正干什么要看括号里。用「有写关键字就算写」兜住。
                return tokens.stream().anyMatch(SqlInspector::isWriteVerb) ? Kind.DML : Kind.QUERY;
            case "call":
            case "exec":
            case "execute":
            case "{call":
                return Kind.OTHER;
            default:
                return Kind.OTHER;
        }
    }

    private static boolean isWriteVerb(String token) {
        return "insert".equals(token) || "update".equals(token)
                || "delete".equals(token) || "merge".equals(token);
    }

    private static Set<String> identifiersOf(List<String> tokens) {
        Set<String> ids = new LinkedHashSet<>(tokens.size() * 2);
        for (String t : tokens) {
            if (t.isEmpty() || isNumeric(t)) {
                continue;
            }
            ids.add(t);
            int dot = t.lastIndexOf('.');
            if (dot > 0 && dot < t.length() - 1) {
                // public.supply_order 也要能被 supply_order 这条规则命中
                ids.add(t.substring(dot + 1));
            }
        }
        return ids;
    }

    private static boolean isNumeric(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                return false;
            }
        }
        return true;
    }

    /**
     * 剥掉注释与单引号字符串，其他字符原样保留（长度会变，无所谓）。
     *
     * <p>双引号与反引号<b>不剥</b>：它们在 PostgreSQL / MySQL 里是标识符引用，
     * 剥掉反而会让 {@code update "supply_order"} 逃过黑名单。
     */
    private static String stripCommentsAndStrings(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
            } else if (c == '#') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                out.append(' ');
            } else if (c == '\'') {
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (d == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            // '' 是一个转义的单引号，不结束字符串
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 切出标识符：字母、数字、下划线、{@code $}、{@code .}、引号、反引号、方括号都算词内字符。
     */
    private static List<String> tokenize(String code) {
        List<String> tokens = new ArrayList<>();
        int n = code.length();
        int i = 0;
        while (i < n) {
            char c = code.charAt(i);
            if (!isTokenChar(c)) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && isTokenChar(code.charAt(i))) {
                i++;
            }
            String raw = code.substring(start, i);
            String normalized = normalize(raw);
            if (!normalized.isEmpty()) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.'
                || c == '"' || c == '`' || c == '[' || c == ']';
    }

    private static String normalize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '`' || c == '[' || c == ']') {
                continue;
            }
            sb.append(c);
        }
        // 前后的点（例如 `.5` 这种字面量）去掉，避免产生无意义的标识符
        String v = sb.toString();
        while (v.startsWith(".")) {
            v = v.substring(1);
        }
        while (v.endsWith(".")) {
            v = v.substring(0, v.length() - 1);
        }
        return v.toLowerCase(Locale.ROOT);
    }
}
