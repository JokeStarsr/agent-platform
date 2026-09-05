package com.agent.orchestration.multiagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SharedBlackboardTest {

    private SharedBlackboard bb;

    @BeforeEach
    void setUp() {
        bb = new SharedBlackboard();
    }

    @Test
    void writeAndRead_perRootRunIsolated() {
        bb.write("t1", 1L, "out_0", "阶段A");
        bb.write("t1", 1L, "sub_policy", "政策结果");
        bb.write("t1", 2L, "out_0", "另一个run");

        assertEquals("阶段A", bb.read("t1", 1L, "out_0"));
        assertEquals("政策结果", bb.read("t1", 1L, "sub_policy"));
        assertEquals("另一个run", bb.read("t1", 2L, "out_0"));
        // t1 的 rootRunId=3 无数据
        assertNull(bb.read("t1", 3L, "out_0"));
    }

    @Test
    void clear_removesBoard() {
        bb.write("t1", 1L, "final", "结果");
        bb.clear("t1", 1L);
        assertEquals(0, bb.read("t1", 1L).size());
    }
}