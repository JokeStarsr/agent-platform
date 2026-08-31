package com.agent.capability.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文组装器单测（docs/design/architecture/20260901-memory-context.md §7 AC-2/AC-3）
 * AC-2：长期记忆注入 user prompt；AC-3：超长上下文压缩到预算内且 system 保留。
 */
class ContextAssemblerTest {

    private final ContextAssembler cut = new ContextAssembler();

    @Test
    void ac2_记忆注入_第二场会话携带长期画像() {
        List<String> memories = List.of("[preference] 偏好经济舱", "[identity] 某公司项目经理");
        ContextAssembler.ContextBundle b = cut.assemble("system-role", "安排出差", List.of(), List.of(), memories, List.of());
        assertTrue(b.user().contains("偏好经济舱"), "长期记忆应注入 user prompt");
    }

    @Test
    void 正常未超限_不压缩() {
        ContextAssembler.ContextBundle b = cut.assemble("system", "简单任务", List.of("hi"), List.of(), List.of(), List.of());
        assertTrue(b.actions().isEmpty(), "未超预算不应触发压缩");
        assertTrue((Boolean) b.usage().get("withinBudget"));
    }

    @Test
    void ac3_超长上下文_压缩到预算内_system保留_工具历史被压缩() {
        // 构造严重超预算输入：大量历史 + RAG + 记忆 + 工具
        List<String> history = new ArrayList<>();
        List<String> rag = new ArrayList<>();
        List<String> mem = new ArrayList<>();
        List<String> tools = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            history.add("用户第" + i + "轮：这笔订单怎么退款，多步来回反复沟通，内容很长很啰嗦，请完整保留上下文记忆和意图");
            rag.add("知识库切片" + i + "：关于退款的完整政策说明，包含各种边界情况和费用明细以及办理流程细则");
            mem.add("[偏好] 用户偏好走开通绿色通道且多次强调效率优先的服务方式");
            tools.add("工具 schema " + i + "：search_order 与 refund 与 apply_coupon 的详细参数 JSON Schema 说明文本");
        }

        ContextAssembler.ContextBundle b = cut.assemble(
                "你是客服助手，依据上下文回答。", "帮我处理退款", history, rag, mem, tools, 1200);

        assertFalse(b.actions().isEmpty(), "严重超长必须触发压缩");
        int total = (Integer) b.usage().get("total");
        assertTrue(total <= 1200, "压缩后总 token 应 ≤ 预算，实际 " + total);
        // System 保底保留
        assertTrue(b.system().contains("客服助手"), "System 分片不得被压缩丢弃");
        // 仍拼装出可用的 user prompt（含压缩标记或保留片段）
        assertFalse(b.user().isBlank(), "压缩后仍应产出 user prompt");
    }
}
