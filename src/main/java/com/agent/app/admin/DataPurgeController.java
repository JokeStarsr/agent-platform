package com.agent.app.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 冲刺 2：删除级联控制器（用户数据一键清理）
 * <p>API: DELETE /api/admin/data/{tenantId} 租户全部数据
 *       DELETE /api/admin/data/{tenantId}/users/{userId} 用户数据</p>
 */
@RestController
public class DataPurgeController {

    private final DataPurgeService purgeService;

    public DataPurgeController(DataPurgeService purgeService) {
        this.purgeService = purgeService;
    }

    /**
     * 删除租户所有数据（级联）
     * <p>包括：记忆/向量/缓存/会话/Token/Agent运行/Workflow实例/血缘</p>
     */
    @DeleteMapping("/api/admin/data/{tenantId}")
    public ResponseEntity<Void> purgeTenant(@PathVariable String tenantId) {
        purgeService.purgeTenant(tenantId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 删除指定用户的所有数据（级联）
     * <p>按 tenantId+userId 跨表清理记忆/Agent运行/Workflow等</p>
     */
    @DeleteMapping("/api/admin/data/{tenantId}/users/{userId}")
    public ResponseEntity<Void> purgeUser(
            @PathVariable String tenantId,
            @PathVariable String userId
    ) {
        purgeService.purgeUser(tenantId, userId);
        return ResponseEntity.noContent().build();
    }
}