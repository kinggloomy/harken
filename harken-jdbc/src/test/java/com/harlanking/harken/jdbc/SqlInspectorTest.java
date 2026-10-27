package com.harlanking.harken.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlInspector} 的行为验证。重点不是「解析得全」，而是「该认出来的都认出来，
 * 该放过的一个都不拦」——误报会把工具变得没法用。
 */
class SqlInspectorTest {

    private final SqlInspector inspector = new SqlInspector();

    @Test
    @DisplayName("四种基本语句分类正确")
    void classifiesBasicStatements() {
        assertEquals(SqlInspector.Kind.QUERY, inspector.analyze("select * from demo_order").kind());
        assertEquals(SqlInspector.Kind.DML,
                inspector.analyze("update demo_order set status='PAID' where id=1").kind());
        assertEquals(SqlInspector.Kind.DML,
                inspector.analyze("insert into demo_order(id) values (1)").kind());
        assertEquals(SqlInspector.Kind.DML, inspector.analyze("DELETE FROM demo_order").kind());
        assertEquals(SqlInspector.Kind.DDL, inspector.analyze("drop table demo_order").kind());
        assertEquals(SqlInspector.Kind.DDL, inspector.analyze("truncate table demo_order").kind());
    }

    @Test
    @DisplayName("首关键字大小写与前导空白不影响分类")
    void ignoresCaseAndLeadingWhitespace() {
        SqlInspector.Analysis a = inspector.analyze("\n\n   -- 注释也算前导\n  UpDaTe demo_order set x=1");
        assertEquals(SqlInspector.Kind.DML, a.kind());
        assertEquals("update", a.verb());
    }

    @Test
    @DisplayName("注释里的关键字不算：update 写在注释里不能把 select 变成写操作")
    void commentsAreStripped() {
        SqlInspector.Analysis a = inspector.analyze("-- update supply_order\nselect 1");
        assertEquals(SqlInspector.Kind.QUERY, a.kind());
        assertFalse(a.identifiers().contains("supply_order"), a.identifiers().toString());

        SqlInspector.Analysis b = inspector.analyze("/* delete from supply_order */ select 1");
        assertEquals(SqlInspector.Kind.QUERY, b.kind());
    }

    @Test
    @DisplayName("单引号字符串里的表名不算：注释里写着 supply_order 也不该误报")
    void stringLiteralsAreStripped() {
        SqlInspector.Analysis a = inspector.analyze(
                "update demo_order set note = 'supply_order 已发货' where id = 1");
        assertEquals(SqlInspector.Kind.DML, a.kind());
        assertFalse(a.identifiers().contains("supply_order"), a.identifiers().toString());
        assertTrue(a.identifiers().contains("demo_order"));
    }

    @Test
    @DisplayName("字符串里的转义单引号不会提前结束字符串")
    void handlesEscapedQuotes() {
        SqlInspector.Analysis a = inspector.analyze(
                "update demo_order set note = 'it''s supply_order' where id = 1");
        assertFalse(a.identifiers().contains("supply_order"), a.identifiers().toString());
    }

    @Test
    @DisplayName("双引号与反引号是标识符，不能剥掉，否则 update \"supply_order\" 会逃过黑名单")
    void quotedIdentifiersAreKept() {
        assertTrue(inspector.analyze("update \"supply_order\" set x=1").identifiers().contains("supply_order"));
        assertTrue(inspector.analyze("update `supply_order` set x=1").identifiers().contains("supply_order"));
        assertTrue(inspector.analyze("delete from [supply_order]").identifiers().contains("supply_order"));
    }

    @Test
    @DisplayName("schema.table 同时给出全名与裸表名")
    void keepsBothQualifiedAndBareNames() {
        SqlInspector.Analysis a = inspector.analyze("update public.supply_order set x=1");
        assertTrue(a.identifiers().contains("public.supply_order"), a.identifiers().toString());
        assertTrue(a.identifiers().contains("supply_order"), a.identifiers().toString());
    }

    @Test
    @DisplayName("子查询与 join 里的表都会出现，黑名单不必只盯 update 后面那个")
    void findsTablesAnywhere() {
        SqlInspector.Analysis a = inspector.analyze(
                "delete from demo_order where id in (select id from supply_order)");
        assertTrue(a.identifiers().contains("supply_order"), a.identifiers().toString());
    }

    @Test
    @DisplayName("CTE 里带 update 才算写，纯 select 的 with 是读")
    void withClause() {
        assertEquals(SqlInspector.Kind.QUERY, inspector.analyze(
                "with t as (select id from demo_order) select * from t").kind());
        assertEquals(SqlInspector.Kind.DML, inspector.analyze(
                "with t as (select id from demo_order) update demo_order set x=1").kind());
    }

    @Test
    @DisplayName("select ... for update 仍然算读：它不改数据")
    void forUpdateIsStillAQuery() {
        assertEquals(SqlInspector.Kind.QUERY,
                inspector.analyze("select * from demo_order where id=1 for update").kind());
    }

    @Test
    @DisplayName("认不出来的语句不拦：存储过程调用交给人工判断")
    void unknownStatements() {
        assertEquals(SqlInspector.Kind.OTHER, inspector.analyze("call do_something(1)").kind());
        assertEquals(SqlInspector.Kind.OTHER, inspector.analyze("").kind());
        assertEquals(SqlInspector.Kind.OTHER, inspector.analyze(null).kind());
    }

    @Test
    @DisplayName("writes() 只对 DML/DDL 为真")
    void writesFlag() {
        assertFalse(inspector.analyze("select 1").writes());
        assertTrue(inspector.analyze("update t set a=1").writes());
        assertTrue(inspector.analyze("create table t(a int)").writes());
        assertFalse(inspector.analyze("call p()").writes());
    }

    @Test
    @DisplayName("数字不当成标识符，避免 ids 里塞满 1 2 3")
    void numbersAreNotIdentifiers() {
        SqlInspector.Analysis a = inspector.analyze("update demo_order set amount = 100.50 where id = 7");
        assertFalse(a.identifiers().contains("100.50"), a.identifiers().toString());
        assertFalse(a.identifiers().contains("7"), a.identifiers().toString());
    }
}
