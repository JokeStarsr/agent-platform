package com.agent.tool.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * L5 执行沙箱：SQL 只读通道（docs/design/security/20260905-sandbox.md §4）。
 * <p>纵深防御：①AST 白名单（SqlSanitizer）→ ②连接级只读（Connection.setReadOnly）
 * + 事务级 {@code BEGIN READ ONLY} 包裹 → ③强制 LIMIT（SqlSanitizer 注入）→ ④结果行数截断 + 敏感列脱敏。</p>
 */
@Service
public class SqlSandboxService {

    private static final Logger log = LoggerFactory.getLogger(SqlSandboxService.class);

    /** 敏感列黑名单：返回 `***` 脱敏 + 审计告警 */
    private static final Set<String> SENSITIVE_COLUMNS = Set.of(
            "password", "pwd", "token", "api_key", "apikey", "secret", "credit_card", "card_no", "id_card");

    private final SandboxProperties props;
    private final DataSource dataSource;

    public SqlSandboxService(SandboxProperties props, DataSource dataSource) {
        this.props = props;
        this.dataSource = dataSource;
    }

    public record SqlResult(List<String> columns, List<List<Object>> rows, int rowCount, boolean truncated,
                            long durationMs) {
    }

    public SqlResult execute(String sql, Integer maxRows) {
        SqlSanitizer.CheckResult check = SqlSanitizer.check(sql, maxRows);
        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            conn.setReadOnly(true);
            conn.setAutoCommit(false);
            // BEGIN READ ONLY：连接只读 + 事务只读双道（即便账号有写权限也被拒）
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(props.getSqlTimeoutSec());
                st.execute("BEGIN READ ONLY");
                boolean hasRow = st.execute(check.sql());
                List<String> columns = new ArrayList<>();
                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                if (hasRow) {
                    try (ResultSet rs = st.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int n = md.getColumnCount();
                        for (int i = 1; i <= n; i++) {
                            columns.add(md.getColumnLabel(i));
                        }
                        int count = 0;
                        while (rs.next() && count < check.maxRows()) {
                            List<Object> row = new ArrayList<>(n);
                            for (int i = 1; i <= n; i++) {
                                String col = columns.get(i - 1);
                                Object v = rs.getObject(i);
                                row.add(isSensitive(col) ? "***" : v);
                            }
                            rows.add(row);
                            count++;
                        }
                        if (rs.next() || count == check.maxRows()) {
                            truncated = true;
                        }
                    }
                }
                // 结束只读事务（回滚等效，防隐式提交）
                try {
                    st.execute("ROLLBACK");
                    conn.setAutoCommit(true);
                } catch (Exception ignored) { }
                return new SqlResult(columns, rows, rows.size(), truncated,
                        System.currentTimeMillis() - start);
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException re && re.getMessage() != null
                    && re.getMessage().contains("只读")) {
                throw re;
            }
            throw new com.agent.common.BizException(400, "SQL 执行失败: " + e.getMessage());
        }
    }

    private boolean isSensitive(String col) {
        if (col == null) {
            return false;
        }
        String c = col.toLowerCase();
        for (String s : SENSITIVE_COLUMNS) {
            if (c.contains(s)) {
                return true;
            }
        }
        return false;
    }

    public java.util.Map<String, Object> health() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("maxRows", props.getSqlMaxRows());
        m.put("timeoutSec", props.getSqlTimeoutSec());
        m.put("readonly", true);
        return m;
    }
}