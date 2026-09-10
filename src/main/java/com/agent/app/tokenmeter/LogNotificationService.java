package com.agent.app.tokenmeter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * L7 数据层：日志通知服务实现（W17）
 * <p>v1 实现：仅记录日志。可后续扩展为邮件/Webhook/短信等通知渠道。</p>
 */
@Service
public class LogNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationService.class);

    @Override
    public void sendBudgetAlert(String tenantId, String alertType, BigDecimal usageRate,
                                BigDecimal todayCost, BigDecimal dailyBudget) {
        String level = "warning".equals(alertType) ? "预警" : "超支";
        String emoji = "warning".equals(alertType) ? "⚠️" : "🚨";

        log.info("{} 预算{}告警: tenant={}, 使用率={}%, 今日已用={}元, 日预算={}元",
                emoji, level, tenantId, usageRate, todayCost, dailyBudget);

        // TODO: 扩展为邮件/Webhook/短信通知
        // - 邮件：读取租户配置的邮箱地址，发送邮件
        // - Webhook：读取租户配置的 Webhook URL，POST JSON
        // - 短信：读取租户配置的手机号，发送短信
    }
}
