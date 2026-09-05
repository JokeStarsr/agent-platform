package com.agent.orchestration.multiagent;

import com.agent.data.multiagent.MultiAgentRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MultiAgentServiceTest {

    private MultiAgentRunRepository repo;
    private SharedBlackboard bb;
    private MultiAgentService svc;

    private com.agent.data.multiagent.MultiAgentRunRepository.RunRow makeRow(long id) {
        return new com.agent.data.multiagent.MultiAgentRunRepository.RunRow(
                id, "t1", "supervisor", "规划一次北京出差", "sv_delegator",
                null, null, "PLANNING", null, 0, 10000, null, null);
    }

    @BeforeEach
    void setUp() {
        repo = mock(MultiAgentRunRepository.class);
        bb = new SharedBlackboard();
        svc = new MultiAgentService(repo, bb);
        when(repo.create(any())).thenReturn(makeRow(1L));
        when(repo.findById(1L)).thenReturn(Optional.of(makeRow(1L)));
    }

    @Test
    void supervisor_submitCompletes_blackboardHasSubResults() throws Exception {
        long root = svc.submit("t1", new MultiAgentService.SubmitReq(
                "supervisor", "规划一次北京出差", "sv_delegator", null));
        assertEquals(1L, root);

        // 异步线程执行，等待完成
        Thread.sleep(800);
        // updateFinal 被调用（COMPLETED）
        verify(repo, atLeastOnce()).updateFinal(eq(1L), anyString(), anyInt());
        // 黑板有子结果
        assertNotNull(bb.read("t1", 1L, "sub_sv_policy"));
        assertNotNull(bb.read("t1", 1L, "sub_sv_compare"));
        assertNotNull(bb.read("t1", 1L, "sub_sv_final"));
    }

    @Test
    void pipeline_submitCompletes_stagesProduceOutput() throws Exception {
        var stages = java.util.List.of(
                new MultiAgentService.PipelineStage("sv_summarize", "请总结：{prev}", null),
                new MultiAgentService.PipelineStage("sv_translate", "翻译：{prev}", null));
        long root = svc.submit("t1", new MultiAgentService.SubmitReq(
                "pipeline", "今日三号会议室会议纪要", "sv_pipeline", stages));
        assertEquals(1L, root);

        Thread.sleep(800);
        verify(repo, atLeastOnce()).updateFinal(eq(1L), anyString(), anyInt());
        assertNotNull(bb.read("t1", 1L, "out_0"));
        assertNotNull(bb.read("t1", 1L, "out_1"));
    }

    @Test
    void unknownTopology_rejected() {
        assertThrows(com.agent.common.BizException.class, () ->
                svc.submit("t1", new MultiAgentService.SubmitReq("debate", "x", "app", null)));
    }

    @Test
    void detail_missingTenant_404() {
        assertThrows(com.agent.common.BizException.class, () -> svc.detail("t2", 1L));
    }
}