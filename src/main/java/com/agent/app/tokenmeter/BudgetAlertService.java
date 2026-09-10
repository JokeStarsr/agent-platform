package com.agent.app.tokenmeter;

import com.agent.data.openplatform.TenantQuotaRepository;
import com.agent.data.openplatform.TenantQuotaRepository.TenantQuota;
import com.agent.data.tokenmeter.BudgetAlertHistoryRepository;
import com.agent.data.tokenmeter.TokenUsageDailyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * L7 数据层：预算告警服务（W17）
 * <p>每次 Token 计量后检查租户预算使用率，触发 80%/100% 两档告警。
 * 同一租户同一档位每日仅告警一次（去重）。</p>
 */
@Service
public class BudgetAlertService {

    private static final Logger log = LoggerFactory.getLogger(BudgetAlertService.class);

    private final TenantQuotaRepository quotaRepo;
    private final TokenUsageDailyRepository tokenUsageDailyRepo;
    private final BudgetAlertHistoryRepository alertHistoryRepo;
    private final NotificationService notificationService;

    public BudgetAlertService(TenantQuotaRepository quotaRepo,
                              TokenUsageDailyRepository tokenUsageDailyRepo,
                              BudgetAlertHistoryRepository alertHistoryRepo,
                              NotificationService notificationService) {
        this.quotaRepo = quotaRepo;
        this.tokenUsageDailyRepo = tokenUsageDailyRepo;
        this.alertHistoryRepo = alertHistoryRepo;
        this.notificationService = notificationService;
    }

    /**
     * 检查租户预算（每次 Token 计量后调用）。
     */
    public void check(String tenantId) {
        try {
            // 1. 查询租户配额
            Optional<TenantQuota> quotaOpt = quotaRepo.findByTenantId(tenantId);
            if (quotaOpt.isEmpty()) {
                return;  // 未配置配额，跳过
            }

            TenantQuota quota = quotaOpt.get();
            BigDecimal dailyBudget = quota.dailyBudget();
            if (dailyBudget.compareTo(BigDecimal.ZERO) <= 0) {
                return;  // 预算为 0 或负数，跳过
            }

            // 2. 查询今日已用费用
            BigDecimal todayCost = tokenUsageDailyRepo.getTodayCost(tenantId, LocalDate.now());

            // 3. 计算使用率
            BigDecimal usageRate = todayCost
                    .divide(dailyBudget, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);

            // 4. 检查 80% 预警
            if (usageRate.compareTo(BigDecimal.valueOf(80)) >= 0) {
                sendAlertIfNotSentToday(tenantId, "warning", usageRate, todayCost, dailyBudget);
            }

            // 5. 检查 100% 超支
            if (usageRate.compareTo(BigDecimal.valueOf(100)) >= 0) {
                sendAlertIfNotSentToday(tenantId, "critical", usageRate, todayCost, dailyBudget);
                // 熔断：标记租户为"预算超支"
                quotaRepo.markBudgetExceeded(tenantId);
                log.warn("租户预算超支熔断: tenant={}, usage={}%, cost={}/{}",
                        tenantId, usageRate, todayCost, dailyBudget);
            }
        } catch (Exception e) {
            // 告警失败不应影响主流程
            log.error("预算告警检查失败: tenant={}, err={}", tenantId, e.getMessage());
        }
    }

    /**
     * 发送告警（如果今日未发送同类型告警）。
     */
    private void sendAlertIfNotSentToday(String tenantId, String alertType,
                                         BigDecimal usageRate, BigDecimal todayCost, BigDecimal dailyBudget) {
        // 检查今日是否已发送同类型告警
        boolean alreadySent = alertHistoryRepo.hasAlertToday(tenantId, alertType, LocalDate.now());
        if (alreadySent) {
            return;
        }

        // 发送通知
        notificationService.sendBudgetAlert(tenantId, alertType, usageRate, todayCost, dailyBudget);

        // 记录告警历史
        alertHistoryRepo.insert(tenantId, alertType, usageRate, todayCost, dailyBudget);

        log.info("预算告警已发送: tenant={}, type={}, usage={}%, cost={}/{}",
                tenantId, alertType, usageRate, todayCost, dailyBudget);
    }

    /**
     * 手动触发预算告警检查（管理员操作）。
     */
    public void manualCheck(String tenantId) {
        check(tenantId);
    }
}
