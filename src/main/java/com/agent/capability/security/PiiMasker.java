package com.agent.capability.security;

import com.agent.common.PiiMaskPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L4 能力层：PII 识别与脱敏（W20，安全红线）
 * <p>识别姓名/身份证/银行卡/手机号等敏感信息并脱敏。
 * 防护面：请求日志自动掩码、LLM 输出内容过滤、审计日志脱敏。</p>
 */
@Service
public class PiiMasker implements PiiMaskPort {

    private static final Logger log = LoggerFactory.getLogger(PiiMasker.class);

    // PII 识别模式
    private static final List<PiiRule> RULES = List.of(
            // 中国大陆身份证号（18 位）
            new PiiRule("id_card", Pattern.compile(
                    "\\b[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]\\b")),
            // 银行卡号（13-19 位数字，含银联 62 开头）
            new PiiRule("card_number", Pattern.compile(
                    "\\b(?:(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|6(?:011|5[0-9]{2})[0-9]{12}|3[47][0-9]{13})|62[0-9]{14,17})\\b")),
            // 手机号（中国大陆 11 位）
            new PiiRule("phone", Pattern.compile(
                    "\\b(?:\\+?86[- ]?)?1[3-9]\\d{9}\\b")),
            // 邮箱
            new PiiRule("email", Pattern.compile(
                    "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")),
            // IPv4 地址
            new PiiRule("ip", Pattern.compile(
                    "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")),
            // 密码/密钥（"password:"、"api key:" 后跟值）
            new PiiRule("credential", Pattern.compile(
                    "(?i)(password|passwd|pwd|api[_-]?key|key|secret|access[_-]?key|token)\\s*[:=]\\s*\\S+"))
    );

    // 脱敏后标记
    private static final String MASK = "***";

    /**
     * 脱敏结果。
     */
    public record MaskResult(String maskedText, List<Map<String, String>> findings) {
    }

    /**
     * 对文本进行 PII 脱敏。
     *
     * @param text 原始文本
     * @return 脱敏后的文本 + 发现的 PII 列表
     */
    public MaskResult mask(String text) {
        if (text == null || text.isBlank()) {
            return new MaskResult(text, List.of());
        }

        List<Map<String, String>> findings = new ArrayList<>();
        String masked = text;

        for (PiiRule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(masked);
            java.util.Set<Integer> found = new java.util.LinkedHashSet<>();
            while (matcher.find()) {
                String match = matcher.group();
                // 避免重复（同一匹配可能被多条规则命中，如手机号与数字）
                if (!found.contains(match.hashCode())) {
                    found.add(match.hashCode());
                    findings.add(Map.of(
                            "type", rule.type(),
                            "value", maskValue(match, rule.type()),
                            "position", String.valueOf(matcher.start())
                    ));
                }
            }
            masked = rule.pattern().matcher(masked).replaceAll(MASK);
        }

        return new MaskResult(masked, findings);
    }

    /**
     * 接口实现：脱敏（供 AuditLogAspect 日志自动掩码）。
     */
    @Override
    public String maskSensitive(String text) {
        return mask(text).maskedText();
    }

    /**
     * 判断文本是否包含 PII（接口实现）。
     */
    @Override
    public boolean containsPii(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (PiiRule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 脱敏值展示（保留部分可见便于核对）。
     */
    private String maskValue(String value, String type) {
        return switch (type) {
            case "phone" -> value.length() > 7
                    ? value.substring(0, 3) + "****" + value.substring(value.length() - 4) : MASK;
            case "email" -> {
                int idx = value.indexOf('@');
                yield idx > 1 ? value.substring(0, 1) + "***" + value.substring(idx - 1) : MASK;
            }
            case "id_card" -> value.length() > 8
                    ? value.substring(0, 4) + "**********" + value.substring(value.length() - 4) : MASK;
            default -> MASK;
        };
    }

    private record PiiRule(String type, Pattern pattern) {
    }
}