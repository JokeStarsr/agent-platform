package com.agent.data.policy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * L7 数据层：差旅政策规则（t_policy_rule）
 * 对应 docs/design/architecture/20260901-w8-trip-scenario.md §2.1/§4.1。tenant_id 行级隔离。
 */
@Repository
public class PolicyRuleRepository {

    private final JdbcTemplate jdbc;

    public PolicyRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PolicyRule(long id, String tenantId, String ruleCode, String dimension,
                             String operator, String thresholdValue, String message, boolean enabled) {
    }

    private static final RowMapper<PolicyRule> ROW = (rs, i) -> new PolicyRule(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("rule_code"),
            rs.getString("dimension"),
            rs.getString("operator"),
            rs.getString("threshold_value"),
            rs.getString("message"),
            rs.getBoolean("enabled"));

    public List<PolicyRule> rulesByDimension(String tenantId, String dimension) {
        return jdbc.query("""
                  SELECT * FROM t_policy_rule
                   WHERE tenant_id = ? AND dimension = ? AND enabled = TRUE
                   ORDER BY id
                  """, ROW, tenantId, dimension);
    }

    public List<PolicyRule> allRules(String tenantId) {
        return jdbc.query("""
                  SELECT * FROM t_policy_rule WHERE tenant_id = ? AND enabled = TRUE ORDER BY dimension, id
                  """, ROW, tenantId);
    }

    public Optional<PolicyRule> find(String tenantId, String ruleCode) {
        return jdbc.query("SELECT * FROM t_policy_rule WHERE tenant_id = ? AND rule_code = ?",
                ROW, tenantId, ruleCode).stream().findFirst();
    }

    public long upsert(String tenantId, String ruleCode, String dimension, String operator,
                       String thresholdValue, String message) {
        jdbc.update("""
                  INSERT INTO t_policy_rule (tenant_id, rule_code, dimension, operator, threshold_value, message)
                  VALUES (?, ?, ?, ?, ?, ?)
                  ON CONFLICT (tenant_id, rule_code) DO UPDATE SET
                    dimension = EXCLUDED.dimension, operator = EXCLUDED.operator,
                    threshold_value = EXCLUDED.threshold_value, message = EXCLUDED.message,
                    enabled = TRUE, updated_at = now()
                  """, tenantId, ruleCode, dimension, operator, thresholdValue, message);
        Long id = jdbc.queryForObject(
                "SELECT id FROM t_policy_rule WHERE tenant_id = ? AND rule_code = ?",
                Long.class, tenantId, ruleCode);
        return id == null ? 0 : id;
    }
}