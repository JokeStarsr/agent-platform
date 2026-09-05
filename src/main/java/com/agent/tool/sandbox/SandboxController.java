package com.agent.tool.sandbox;

import com.agent.common.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * L5 执行沙箱：管理 API（docs/design/security/20260905-sandbox.md §6，统一 Result<T>）。
 * 代码执行 / SQL 查询 / 健康状态。
 */
@RestController
@RequestMapping("/api/sandbox")
public class SandboxController {

    private final CodeSandboxService codeSandbox;
    private final SqlSandboxService sqlSandbox;

    public SandboxController(CodeSandboxService codeSandbox, SqlSandboxService sqlSandbox) {
        this.codeSandbox = codeSandbox;
        this.sqlSandbox = sqlSandbox;
    }

    public record CodeRequest(String language, String code, String stdin) {
    }

    /** 代码沙箱执行（python / shell） */
    @PostMapping("/code")
    public Result<CodeSandboxService.CodeResult> code(@RequestBody CodeRequest req) {
        CodeSandboxService.CodeResult r = codeSandbox.execute(
                req.language() == null ? "python" : req.language(),
                req.code() == null ? "" : req.code(),
                req.stdin());
        return Result.ok(r);
    }

    public record SqlRequest(String sql, Integer maxRows) {
    }

    /** SQL 沙箱查询（只读通道 + AST 白名单 + 强制 LIMIT + 脱敏） */
    @PostMapping("/sql")
    public Result<SqlSandboxService.SqlResult> sql(@RequestBody SqlRequest req) {
        return Result.ok(sqlSandbox.execute(req.sql(), req.maxRows()));
    }

    /** 沙箱健康状态 */
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(codeSandbox.health());
        m.put("sql", sqlSandbox.health());
        return Result.ok(m);
    }
}