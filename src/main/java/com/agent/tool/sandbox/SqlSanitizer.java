package com.agent.tool.sandbox;

import com.agent.common.BizException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

import java.util.List;
import java.util.regex.Pattern;

/**
 * L5 执行沙箱：SQL AST 白名单（docs/design/security/20260905-sandbox.md §4.2）。
 * <p>仅允许 SELECT/WITH；拒绝写/DDL/CALL/多语句/FOR UPDATE；强制 LIMIT（无 LIMIT 注入默认上限）。
 * 解析失败一律拒绝（宁可误拦）。</p>
 */
public final class SqlSanitizer {

    public static final int DEFAULT_MAX_ROWS = 100;

    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|--.*?$", Pattern.MULTILINE);
    private static final Pattern FOR_UPDATE = Pattern.compile("(?i)\\bfor\\s+update\\b");
    private static final Pattern SEMICOLON = Pattern.compile(";");

    private SqlSanitizer() {
    }

    public record CheckResult(String sql, int maxRows) {
    }

    /** 校验并规整单条 SELECT；不合法抛 BizException(400/403, 原因) */
    public static CheckResult check(String sql, int maxRows) {
        if (sql == null || sql.isBlank()) {
            throw new BizException(400, "SQL 不能为空");
        }
        int limit = Math.min(Math.max(1, maxRows <= 0 ? DEFAULT_MAX_ROWS : maxRows), 500);
        // 1) 去注释（防注释绕过关键字检测）
        String cleaned = COMMENT.matcher(sql).replaceAll(" ");
        // 2) 多语句拒绝（分号后还有非空内容即判定注入）
        List<String> parts = java.util.Arrays.stream(SEMICOLON.split(cleaned))
                .map(String::trim).filter(p -> !p.isEmpty()).toList();
        if (parts.size() > 1) {
            throw new BizException(403, "仅允许单条语句，检测到多语句注入");
        }
        // 3) 解析（失败=语法脏，拒绝）
        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(cleaned);
        } catch (Exception e) {
            throw new BizException(400, "SQL 解析失败：非合法语句");
        }
        // 4) 仅 SELECT/WITH
        if (!(stmt instanceof Select select)) {
            throw new BizException(403, "仅允许 SELECT/WITH 查询语句");
        }
        // 5) FOR UPDATE 拒绝（锁行副作用）
        if (FOR_UPDATE.matcher(select.toString()).find()) {
            throw new BizException(403, "不允许 SELECT FOR UPDATE");
        }
        // 6) 强制 LIMIT：无 → 注入；有 → 截断到上限
        String normalized;
        if (select.getPlainSelect() != null && select.getPlainSelect().getLimit() != null) {
            normalized = select.toString();
        } else {
            // 无 LIMIT：追加（对 WITH 也有效）
            String prefix = cleaned.trim().endsWith(";") ? cleaned.trim().substring(0, cleaned.trim().length() - 1)
                    : cleaned.trim();
            normalized = prefix + " LIMIT " + limit;
        }
        return new CheckResult(normalized, limit);
    }
}