package com.agent.app.tokenmeter;

import java.math.BigDecimal;

/**
 * L7 数据层：通知服务接口（W17）
 * <p>发送预算告警通知（邮件/Webhook）。</p>
 */
public interface NotificationService {

    /**
     * 发送预算告警。
     *
     * @param tenantId     租户 ID
     * @param alertType    告警类型（warning/critical）
     * @param usageRate    使用率（如 85.5）
     * @param todayCost    今日已用费用
     * @param dailyBudget  日预算
     */
    void sendBudgetAlert(String tenantId, String alertType, BigDecimal usageRate,
                         BigDecimal todayCost, BigDecimal dailyBudget);
}
