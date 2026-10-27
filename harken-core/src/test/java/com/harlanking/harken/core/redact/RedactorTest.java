package com.harlanking.harken.core.redact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏规则的边界测试。
 *
 * <p>这组测试的重点不是「能掩掉密码」，而是<b>不该掩的别掩</b>：
 * 脱敏过度会让审计失去价值 —— 一段全 {@code ***} 的脚本等于没有记录。
 */
class RedactorTest {

    @Test
    @DisplayName("双引号赋值：保留键名，掩掉值")
    void masksDoubleQuotedValue() {
        assertEquals("password=\"***\"", Redactor.redact("password=\"hunter2\""));
    }

    @Test
    @DisplayName("单引号与 Groovy 具名参数写法都要认")
    void masksSingleQuoteAndNamedArgument() {
        assertEquals("def secret = '***'", Redactor.redact("def secret = 's3cr3t'"));
        assertEquals("connect(user: 'x', password: '***')",
                Redactor.redact("connect(user: 'x', password: 'p@ss')"));
    }

    @Test
    @DisplayName("大小写不敏感，键名原样保留")
    void keepsOriginalKeyCase() {
        assertEquals("PASSWORD = \"***\"", Redactor.redact("PASSWORD = \"abc123\""));
        assertEquals("accessKey=\"***\"", Redactor.redact("accessKey=\"AKIA1234\""));
    }

    @Test
    @DisplayName("不带引号的字面量也要掩")
    void masksUnquotedLiteral() {
        assertEquals("password = ***", Redactor.redact("password = abc123"));
        assertEquals("db.pwd=***;", Redactor.redact("db.pwd=qwerty;"));
    }

    @Test
    @DisplayName("RHS 是表达式（不含密钥字面量）时不掩，避免留下残句")
    void leavesExpressionUntouched() {
        String s = "password = System.getenv(\"DB_PASSWORD\")";
        assertEquals(s, Redactor.redact(s),
                "右边没有字面量密钥，掩掉只会得到 'password = ***(...)' 这种更难读的结果");
    }

    @Test
    @DisplayName("JDBC URL 只掩 userinfo 里的密码，host 与库名保留")
    void masksUrlUserInfo() {
        String text = "jdbc:mysql://root:P@ssw0rd@10.0.0.5:3306/order_db";
        String masked = Redactor.redact(text);
        assertEquals("jdbc:mysql://root:***@10.0.0.5:3306/order_db", masked);
    }

    @Test
    @DisplayName("Bearer / Basic / 裸 JWT 按形态掩")
    void masksTokenForms() {
        assertEquals("Authorization: Bearer ***",
                Redactor.redact("Authorization: Bearer abcdefghijklmnop"));
        assertEquals("Authorization: Basic ***",
                Redactor.redact("Authorization: Basic YWRtaW46cHcxMjM="));
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        assertFalse(Redactor.redact("token=" + jwt).contains("eyJhbGciOiJIUzI1NiJ9"));
    }

    @Test
    @DisplayName("幂等：掩过的文本再掩一次不变")
    void isIdempotent() {
        String once = Redactor.redact("password='abc' and secret=\"def\"");
        assertEquals(once, Redactor.redact(once));
    }

    @Test
    @DisplayName("不误伤：passwordHash / tokenizer / 普通文本原样返回")
    void doesNotOverMask() {
        String script = "def passwordHash = 'sha256:abc'\n"
                + "def tokenizer = new StringTokenizer(s)\n"
                + "orderService.count()";
        assertEquals(script, Redactor.redact(script), "没有任何敏感赋值时文本必须一字不改");
    }

    @Test
    @DisplayName("错误栈里的连接串被掩，栈结构保留")
    void masksStackTrace() {
        String stack = "java.sql.SQLException: Access denied for user 'root@10.0.0.5' "
                + "(using password: YES)\n\tat jdbc:postgresql://root:TopSecret1@db:5432/x";
        String masked = Redactor.redact(stack);
        assertFalse(masked.contains("TopSecret1"), "密码必须消失");
        assertTrue(masked.contains("java.sql.SQLException"), "栈类型不能被抹掉");
        assertTrue(masked.contains("db:5432/x"), "库名要留着，否则没法定位问题");
    }

    @Test
    @DisplayName("JSON 报文体里的密码也要掩")
    void masksJsonBody() {
        assertEquals("{\"user\":\"a\",\"password\":\"***\"}",
                Redactor.redact("{\"user\":\"a\",\"password\":\"pw123\"}"));
    }

    @Test
    @DisplayName("null 与空串原样返回")
    void handlesNullAndEmpty() {
        assertNull(Redactor.redact(null));
        assertEquals("", Redactor.redact(""));
    }
}
