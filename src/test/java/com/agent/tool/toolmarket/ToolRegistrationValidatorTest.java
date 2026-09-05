package com.agent.tool.toolmarket;

import com.agent.common.BizException;
import com.agent.tool.toolmarket.ToolMarketService.ToolRegRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolRegistrationValidatorTest {

    private ToolRegRequest valid() {
        return new ToolRegRequest("report_daily_summary", "日报摘要",
                "生成每日业务报表摘要，供运营查看关键指标趋势（演示工具）",
                "data",
                "{\"type\":\"object\",\"properties\":{\"days\":{\"type\":\"integer\"}}}",
                "READ", "MOCK",
                List.of(Map.of("name", "正常", "arguments", Map.of("days", 7), "expectCode", 0)));
    }

    @Test
    void validRequest_passes() {
        assertDoesNotThrow(() -> ToolRegistrationValidator.validate(valid()));
    }

    @Test
    void invalidName_rejected() {
        ToolRegRequest r = valid();
        assertThrows(BizException.class, () ->
                ToolRegistrationValidator.validate(new ToolRegRequest(
                        "Bad-Name", r.displayName(), r.description(), r.category(),
                        r.parameters(), r.permission(), r.behavior(), r.testcases())));
    }

    @Test
    void shortDescription_rejected() {
        ToolRegRequest r = valid();
        assertThrows(BizException.class, () ->
                ToolRegistrationValidator.validate(new ToolRegRequest(
                        r.toolName(), r.displayName(), "太短", r.category(),
                        r.parameters(), r.permission(), r.behavior(), r.testcases())));
    }

    @Test
    void badPermission_rejected() {
        ToolRegRequest r = valid();
        assertThrows(BizException.class, () ->
                ToolRegistrationValidator.validate(new ToolRegRequest(
                        r.toolName(), r.displayName(), r.description(), r.category(),
                        r.parameters(), "SUPER", r.behavior(), r.testcases())));
    }

    @Test
    void invalidSchema_rejected() {
        ToolRegRequest r = valid();
        assertThrows(BizException.class, () ->
                ToolRegistrationValidator.validate(new ToolRegRequest(
                        r.toolName(), r.displayName(), r.description(), r.category(),
                        "{not-valid-json", r.permission(), r.behavior(), r.testcases())));
    }

    @Test
    void missingTestcases_rejected() {
        ToolRegRequest r = valid();
        assertThrows(BizException.class, () ->
                ToolRegistrationValidator.validate(new ToolRegRequest(
                        r.toolName(), r.displayName(), r.description(), r.category(),
                        r.parameters(), r.permission(), r.behavior(), List.of())));
    }
}