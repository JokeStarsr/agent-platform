package com.agent.orchestration.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DAG 解析器单测（docs/design/architecture/20260831-workflow-engine.md §2.1 合法性校验 + 环检测）
 */
class WorkflowDefParserTest {

    private final WorkflowDefParser parser = new WorkflowDefParser();

    @Test
    void 正常解析_线性拓扑_节点类型正确() {
        String def = """
                {"flowId":"linear","timeoutMs":120000,
                 "nodes":[
                   {"id":"a","type":"TOOL","tool":"t1","args":{},"out":"x"},
                   {"id":"b","type":"LLM","prompt":"ok","out":"y"},
                   {"id":"c","type":"HUMAN","title":"确认","escalateAfterMs":1800000}
                 ],
                 "edges":[["a","b"],["b","c"]]}
                """;
        WorkflowGraph g = parser.parse(def);
        assertEquals("linear", g.flowId());
        assertEquals(120_000, g.defaultTimeoutMs());
        assertEquals(3, g.nodes().size());
        assertEquals(NodeType.TOOL, g.nodes().get("a").type());
        assertEquals(NodeType.LLM, g.nodes().get("b").type());
        assertEquals(NodeType.HUMAN, g.nodes().get("c").type());
        assertEquals(2, g.edges().size());
        assertEquals(3, g.order().size());
        assertTrue(g.order().indexOf("a") < g.order().indexOf("b"));
        assertTrue(g.order().indexOf("b") < g.order().indexOf("c"));
    }

    @Test
    void 正常解析_PARALLEL分支展平_虚拟边接入拓扑序() {
        String def = """
                {"flowId":"para",
                 "nodes":[
                   {"id":"n1","type":"TOOL","tool":"t0","args":{},"out":"pre"},
                   {"id":"n2","type":"PARALLEL","branches":[
                       {"id":"n2a","type":"TOOL","tool":"ta","args":{},"out":"a"},
                       {"id":"n2b","type":"TOOL","tool":"tb","args":{},"out":"b"}
                   ]},
                   {"id":"n3","type":"HUMAN","title":"ok"}
                 ],
                 "edges":[["n2","n3"],["n2a","n3"],["n2b","n3"],["n1","n2"]]}
                """;
        WorkflowGraph g = parser.parse(def);
        assertEquals(5, g.nodes().size()); // n1,n2,n2a,n2b,n3
        assertTrue(g.parallelBranches().containsKey("n2"));
        assertEquals(2, g.parallelBranches().get("n2").size());
        assertTrue(g.order().indexOf("n2") < g.order().indexOf("n2a"));
        assertTrue(g.order().indexOf("n2a") < g.order().indexOf("n3"));
        assertTrue(g.order().indexOf("n1") < g.order().indexOf("n2"));
    }

    @Test
    void 正常解析_CONDITION节点_不出现在edges中() {
        String def = """
                {"flowId":"cond",
                 "nodes":[
                   {"id":"c1","type":"CONDITION","branches":[
                       {"branchId":"b1","expr":"exists($.x)","next":"n_yes"},
                       {"branchId":"b2","expr":"default","next":"n_no"}
                   ]},
                   {"id":"n_yes","type":"TOOL","tool":"ty","args":{},"out":"o1"},
                   {"id":"n_no","type":"TOOL","tool":"tn","args":{},"out":"o2"}
                 ],
                 "edges":[]}
                """;
        WorkflowGraph g = parser.parse(def);
        assertEquals(3, g.nodes().size());
        assertTrue(g.conditionBranches().containsKey("c1"));
        assertEquals(2, g.conditionBranches().get("c1").size());
        assertEquals(0, g.edges().size()); // CONDITION 不在 edges
        // 拓扑序：c1 必须在 n_yes/n_no 之前
        assertTrue(g.order().indexOf("c1") < g.order().indexOf("n_yes"));
        assertTrue(g.order().indexOf("c1") < g.order().indexOf("n_no"));
    }

    @Test
    void CONDITION节点在edges中出边_抛异常() {
        String def = """
                {"flowId":"bad","nodes":[
                   {"id":"c","type":"CONDITION","branches":[{"branchId":"b","expr":"x","next":"t"}]},
                   {"id":"t","type":"TOOL","tool":"t1","args":{},"out":"o"}
                 ],"edges":[["c","t"]]}
                """;
        assertThrows(IllegalStateException.class, () -> parser.parse(def));
    }

    @Test
    void 存在环_拓扑排序失败() {
        String def = """
                {"flowId":"cycle","nodes":[
                   {"id":"a","type":"TOOL","tool":"t","args":{},"out":"x"},
                   {"id":"b","type":"TOOL","tool":"t","args":{},"out":"x"}
                 ],"edges":[["a","b"],["b","a"]]}
                """;
        assertThrows(IllegalStateException.class, () -> parser.parse(def));
    }

    @Test
    void 边端点不存在_抛异常() {
        String def = """
                {"flowId":"bad","nodes":[
                   {"id":"a","type":"TOOL","tool":"t","args":{},"out":"x"}
                 ],"edges":[["a","ghost"]]}
                """;
        assertThrows(IllegalStateException.class, () -> parser.parse(def));
    }
}
