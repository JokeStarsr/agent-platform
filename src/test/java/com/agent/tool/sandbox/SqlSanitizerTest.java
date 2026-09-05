package com.agent.tool.sandbox;

import com.agent.common.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlSanitizer AST 白名单单测（docs/design/security/20260905-sandbox.md §4.2）
 * 覆盖：合法 SELECT 通过；写/DDL/多语句/注释绕过/FOR UPDATE 全拒绝。
 */
class SqlSanitizerTest {

    @Test
    void plainSelect_passes() {
        var r = SqlSanitizer.check("SELECT * FROM t_app WHERE app_id='x'", 100);
        assertTrue(r.sql().contains("SELECT"));
        assertEquals(100, r.maxRows());
    }

    @Test
    void selectWithoutLimit_getsInjected() {
        var r = SqlSanitizer.check("SELECT id, app_id FROM t_app", 50);
        assertTrue(r.sql().toLowerCase().contains("limit 50"));
    }

    @Test
    void cteWith_passes() {
        assertDoesNotThrow(() -> SqlSanitizer.check(
                "WITH x AS (SELECT app_id FROM t_app) SELECT * FROM x", 100));
    }

    @Test
    void insert_rejected() {
        assertThrows(BizException.class, () ->
                SqlSanitizer.check("INSERT INTO t_app(app_id) VALUES('hack')", 100));
    }

    @Test
    void update_rejected() {
        assertThrows(BizException.class, () ->
                SqlSanitizer.check("UPDATE t_app SET status='ENABLED' WHERE id=1", 100));
    }

    @Test
    void delete_rejected() {
        assertThrows(BizException.class, () ->
                SqlSanitizer.check("DELETE FROM t_tool_grant", 100));
    }

    @Test
    void dropTable_rejected() {
        assertThrows(BizException.class, () ->
                SqlSanitizer.check("DROP TABLE t_tool_invocation", 100));
    }

    @Test
    void createTable_rejected() {
        assertThrows(BizException.class, () ->
                SqlSanitizer.check("CREATE TABLE hacked(id int)", 100));
    }

    @Test
    void truncate_rejected() {
        assertThrows(BizException.class, () ->
                SqlSanitizer.check("TRUNCATE t_app", 100));
    }

    @Test
    void callProcedure_rejected() {
        assertThrows(BizException.class, () ->
                SqlSanitizer.check("CALL evil_proc()", 100));
    }

    @Test
    void forUpdate_rejected() {
        assertThrows(BizException.class, () ->
                SqlSanitizer.check("SELECT * FROM t_app FOR UPDATE", 100));
    }

    @Test
    void multiStatement_rejected() {
        assertThrows(BizException.class, () ->
                SqlSanitizer.check("SELECT 1; DROP TABLE t_app", 100));
    }

    @Test
    void commentBypass_rejected() {
        // 注释后隐藏的写操作（去注释后仍多语句）
        assertThrows(BizException.class, () ->
                SqlSanitizer.check("SELECT 1; /* drop */ DROP TABLE t_app", 100));
    }

    @Test
    void inlineCommentInside_rejectedOrSafe() {
        // 注释仅用于绕过关键字检测：去注释后为 SELECT，应安全通过
        var r = SqlSanitizer.check("SELECT id /* noop */ FROM t_app", 100);
        assertTrue(r.sql().toLowerCase().contains("select"));
    }

    @Test
    void unionInjection_rejected() {
        assertThrows(BizException.class, () ->
                SqlSanitizer.check("SELECT * FROM t_app; UNION SELECT password FROM users", 100));
    }

    @Test
    void garbageSql_rejected() {
        assertThrows(BizException.class, () ->
                SqlSanitizer.check("NOT a real sql statement at all 你好 中文", 100));
    }

    @Test
    void emptySql_rejected() {
        assertThrows(BizException.class, () -> SqlSanitizer.check("", 100));
        assertThrows(BizException.class, () -> SqlSanitizer.check("   ", 100));
    }

    @Test
    void maxRowsClamped() {
        var r = SqlSanitizer.check("SELECT id FROM t_app", 10_000);
        assertEquals(500, r.maxRows()); // 上限截断
    }
}