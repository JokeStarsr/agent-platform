package com.agent.orchestration.skillhub;

import com.agent.orchestration.agent.AgentRuntimeService;
import com.agent.orchestration.multiagent.MultiAgentService;
import com.agent.tool.ToolEngineService;
import com.agent.tool.ToolMeta;
import com.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 写操作预览生成器（W14 P3 硬闸门）。
 * <p>拦截 WRITE/PAYMENT 工具调用，生成结构化 diff 预览：{tool, params, summary, impact}。
 * 前端渲染为"将要做什么"卡片；用户确认后才真正执行。</p>
 */
@Component
public class OperationPreviewGenerator {

    private static final Logger log = LoggerFactory.getLogger(OperationPreviewGenerator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolRegistry toolRegistry;
    private final ToolEngineService toolEngine;

    // 预览存储：previewId -> PreviewData
    private final Map<String, PreviewData> previews = new ConcurrentHashMap<>();

    public OperationPreviewGenerator(ToolRegistry toolRegistry, ToolEngineService toolEngine) {
        this.toolRegistry = toolRegistry;
        this.toolEngine = toolEngine;
    }

    /** 从 Agent 决策生成预览（若为写操作） */
    public Optional<PreviewResult> generatePreview(String tenantId, String appId,
                                                    String toolName, Map<String, Object> args) {
        Optional<com.agent.tool.AgentTool> toolOpt = toolRegistry.resolve(toolName);
        if (toolOpt.isEmpty()) {
            return Optional.empty();
        }
        com.agent.tool.AgentTool tool = toolOpt.get();
        ToolMeta meta = toolRegistry.metaOf(toolName).orElseThrow();

        // 只拦截写操作
        if (!meta.isWrite()) {
            return Optional.empty();
        }

        // 生成预览数据
        String previewId = UUID.randomUUID().toString();
        String summary = generateSummary(toolName, args);
        String impact = generateImpact(toolName, args);

        PreviewData data = new PreviewData(previewId, tenantId, appId, toolName, args, summary, impact);
        previews.put(previewId, data);

        log.info("生成写操作预览: previewId={} tool={} args={}", previewId, toolName, args);
        return Optional.of(new PreviewResult(previewId, toolName, args, summary, impact));
    }

    /** 用户确认/拒绝预览 */
    public boolean confirmPreview(String previewId, boolean approved) {
        PreviewData data = previews.remove(previewId);
        if (data == null) {
            log.warn("预览不存在或已过期: {}", previewId);
            return false;
        }
        if (!approved) {
            log.info("用户拒绝写操作: previewId={}", previewId);
            return false;
        }
        log.info("用户确认写操作: previewId={}", previewId);
        return true;
    }

    /** 获取预览详情（不消费） */
    public Optional<PreviewData> getPreview(String previewId) {
        return Optional.ofNullable(previews.get(previewId));
    }

    private String generateSummary(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "book_order" -> "预订差旅订单：" + args.getOrDefault("flight", "航班") + " + " + args.getOrDefault("hotel", "酒店");
            case "pay_order" -> "支付订单：" + args.getOrDefault("orderId", "未知") + " 金额：" + args.getOrDefault("amount", "未知");
            case "cancel_order" -> "取消订单：" + args.getOrDefault("orderId", "未知");
            case "send_coupon" -> "发放优惠券：" + args.getOrDefault("userId", "用户") + " 类型：" + args.getOrDefault("couponType", "未知");
            case "refund_order_partial" -> "部分退款：" + args.getOrDefault("orderId", "未知") + " 金额：" + args.getOrDefault("amount", "未知");
            case "email_notify" -> "发送邮件：" + args.getOrDefault("to", "收件人") + " 主题：" + args.getOrDefault("subject", "无");
            case "calendar_create_event" -> "创建日程：" + args.getOrDefault("title", "未知") + " 时间：" + args.getOrDefault("startTime", "未知");
            default -> "执行写操作：" + toolName;
        };
    }

    private String generateImpact(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "book_order" -> "产生实际订单记录，占用库存，可能产生费用";
            case "pay_order" -> "真实扣款，资金流出，不可逆";
            case "cancel_order" -> "订单状态变为取消，可能触发退款流程";
            case "send_coupon" -> "用户账户新增优惠券，影响后续下单优惠";
            case "refund_order_partial" -> "资金退回用户，订单金额减少";
            case "email_notify" -> "邮件将发送至收件人邮箱，不可撤回";
            case "calendar_create_event" -> "日历新增事件，可能触发提醒通知";
            default -> "该操作会修改业务数据，执行后不可撤销";
        };
    }

    public record PreviewResult(String previewId, String tool, Map<String, Object> params,
                                String summary, String impact) {}

    public record PreviewData(String previewId, String tenantId, String appId,
                              String toolName, Map<String, Object> args,
                              String summary, String impact) {}
}