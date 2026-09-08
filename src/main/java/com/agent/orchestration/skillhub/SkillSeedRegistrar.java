package com.agent.orchestration.skillhub;

import com.agent.data.skillhub.SkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 技能种子数据（W14 首发 5 技能）。
 * 启动时自动安装并发布（跑测试用例全绿）。
 */
@Component
public class SkillSeedRegistrar {

    private static final Logger log = LoggerFactory.getLogger(SkillSeedRegistrar.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SkillHubService skillHub;
    private final SkillRepository repo;

    public SkillSeedRegistrar(SkillHubService skillHub, SkillRepository repo) {
        this.skillHub = skillHub;
        this.repo = repo;
    }

    @PostConstruct
    void init() {
        // 清理上次失败残留（无版本记录的 DRAFT 技能），已发布技能保留
        for (var s : repo.list(100, 0, null, null)) {
            if (repo.listVersions(s.id()).isEmpty()) {
                log.warn("清理技能残留: {}", s.name());
                repo.deleteSkill(s.id());
            }
        }

        // 逐个幂等注入：已存在则跳过，缺失则安装并发布
        installIfMissing("trip_advisor", buildTripAdvisor());
        installIfMissing("data_query", buildDataQuery());
        installIfMissing("meeting_notes", buildMeetingNotes());
        installIfMissing("email_draft", buildEmailDraft());
        installIfMissing("faq_answer", buildFaqAnswer());
    }

    private void installIfMissing(String expectedName, String manifestJson) {
        try {
            if (repo.findByName(expectedName).isPresent()) {
                log.debug("技能已存在，跳过: {}", expectedName);
                return;
            }
            var row = skillHub.install(manifestJson, "platform");
            skillHub.publish(row.id());
            log.info("技能种子注入完成: {}", expectedName);
        } catch (Exception e) {
            log.error("技能种子注入失败: {} err={}", expectedName, e.getMessage());
        }
    }

    private String buildTripAdvisor() {
        return json("""
            {
              "name": "trip_advisor",
              "version": "1.0.0",
              "displayName": "差旅助手",
              "description": "结合差旅政策与流程，帮员工规划合规差旅（含审批与下单）",
              "category": "business",
              "orchestration": "multi-agent",
              "permissions": ["READ", "WRITE"],
              "prompts": {
                "system": "你是有差旅政策知识的企业助手，帮员工规划合规差旅方案。",
                "router": "识别用户差旅意图并生成任务分解。"
              },
              "tools": ["policy_query", "compare_flight", "compare_hotel", "book_order", "cancel_order", "notify_user"],
              "kb": ["tc_policy_v3"],
              "testcases": [
                {"name": "合规差旅", "input": "帮我订北京到上海出差往返，住3晚", "expect": "产出合规方案并进入人工确认"},
                {"name": "政策咨询", "input": "北京出差住宿标准是多少", "expect": "返回政策标准"}
              ]
            }
            """);
    }

    private String buildDataQuery() {
        return json("""
            {
              "name": "data_query",
              "version": "1.0.0",
              "displayName": "数据查询",
              "description": "自然语言查询业务数据（走只读 SQL 沙箱），生成报表",
              "category": "data",
              "orchestration": "agent",
              "permissions": ["READ"],
              "prompts": {
                "system": "你是数据分析助手，将自然语言转为只读 SQL 并解释结果。",
                "router": "识别查询意图，生成 SQL。"
              },
              "tools": ["query_order_status", "report_daily_summary"],
              "kb": ["tc_ops_v1"],
              "testcases": [
                {"name": "订单查询", "input": "查询订单 ORD-001 的状态", "expect": "返回订单状态"},
                {"name": "日报生成", "input": "生成今日销售日报", "expect": "返回报表摘要"}
              ]
            }
            """);
    }

    private String buildMeetingNotes() {
        return json("""
            {
              "name": "meeting_notes",
              "version": "1.0.0",
              "displayName": "会议纪要",
              "description": "会议录音/文本转结构化纪要：摘要、决议、行动项、责任人",
              "category": "productivity",
              "orchestration": "pipeline",
              "permissions": ["READ"],
              "prompts": {
                "system": "你是会议纪要生成专家，输出结构化 markdown。",
                "router": "将原文按阶段处理：转写→摘要→决议提取→行动项。"
              },
              "tools": ["sv_summarize", "sv_translate"],
              "kb": [],
              "stages": [
                {"agent": "sv_summarize", "prompt": "将会议内容压缩为要点摘要：{prev}"},
                {"agent": "sv_translate", "prompt": "将摘要整理为结构化纪要（决议/行动项/负责人）：{prev}"}
              ],
              "testcases": [
                {"name": "纪要生成", "input": "会议内容：张三提议下周一发布，李四同意，王五负责测试。", "expect": "输出结构化纪要含决议和行动项"}
              ]
            }
            """);
    }

    private String buildEmailDraft() {
        return json("""
            {
              "name": "email_draft",
              "version": "1.0.0",
              "displayName": "邮件草稿",
              "description": "根据场景生成专业邮件草稿，发送前预览确认",
              "category": "comm",
              "orchestration": "agent",
              "permissions": ["READ", "WRITE"],
              "prompts": {
                "system": "你是商务邮件撰写助手，生成得体、清晰的邮件草稿。",
                "router": "识别邮件场景（跟进/道歉/通知/邀请）并生成草稿。"
              },
              "tools": ["email_notify"],
              "kb": [],
              "testcases": [
                {"name": "跟进邮件", "input": "给客户发跟进邮件，上周发过方案未回复", "expect": "生成礼貌跟进邮件草稿"},
                {"name": "会议邀请", "input": "邀请团队开周会，周一上午10点", "expect": "生成会议邀请邮件"}
              ]
            }
            """);
    }

    private String buildFaqAnswer() {
        return json("""
            {
              "name": "faq_answer",
              "version": "1.0.0",
              "displayName": "知识问答",
              "description": "基于企业知识库（RAG）的通用问答，引用来源",
              "category": "utility",
              "orchestration": "agent",
              "permissions": ["READ"],
              "prompts": {
                "system": "你是企业知识助手，基于检索到的文档回答问题，标注引用。",
                "router": "检索相关文档片段并综合回答。"
              },
              "tools": [],
              "kb": ["tc_policy_v3", "tc_payment_v1", "tc_member_v1", "tc_ops_v1"],
              "testcases": [
                {"name": "政策问答", "input": "公司报销标准里餐费上限多少", "expect": "引用政策文档给出答案"},
                {"name": "会员权益", "input": "钻石会员有什么权益", "expect": "引用会员文档给出答案"}
              ]
            }
            """);
    }

    private String json(String s) {
        try {
            return JSON.writeValueAsString(JSON.readTree(s));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}