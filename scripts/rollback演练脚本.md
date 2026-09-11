# Agent Platform 回滚演练脚本（冲刺4，≤15分钟恢复）

> 版本：v1.0 ｜ 频率：每周一次 ｜ 集成：CI 健康检查 + 版本对比检查

---

## 1. 回滚原则

- **零数据丢失**：仅回滚代码，保留用户数据/会话/知识库
- **最小中断**：目标 ≤15 分钟完成恢复
- **权限冻结**：演练时禁用 API Key 租户，避免真实业务中断
- **版本固定**：始终回滚到已知稳定版本（当前 master）

---

## 2. 回滚指令

```bash
# 停止应用
pkill -f "java.*agent-platform" || true

# 切换到回滚版本
cd /data/agent-platform
git checkout master
git reset --hard $(git rev-parse origin/master)

# 重新构建与启动
mvn clean package -DskipTests -q
nohup java -jar target/agent-platform-*.jar --server.port=8082 > logs/app.log 2>&1 &

# 等待健康检查
sleep 30
curl -f http://localhost:8082/actuator/health || {
    echo "健康检查失败，紧急恢复"
    exit 1
}

# 恢复 API Key 权限（演练时租户被临时禁用）
curl -X POST http://localhost:8082/api/open/keys/enable-tenant \
  -H "Content-Type: application/json" -d '{"tenantId": "tenant-demo"}'

echo "回滚完成"
```

---

## 3. 完整流程

### 阶段 1：准备（2 分钟）
```bash
# 1. 冻结租户
echo "冻结API Key租户（防止演练中实际调用）"
curl -X POST http://localhost:8082/api/open/keys/disable-tenant \
  -H "Content-Type: application/json" -d '{"tenantId": "tenant-demo"}'

# 2. 记录当前版本
CURRENT_VER=$(git rev-parse --short HEAD)
echo "当前版本: $CURRENT_VER"
```

### 阶段 2：部署（3 分钟）
```bash
# 1. 停止应用
systemctl stop agent-platform || docker stop agent-platform || pkill -f agent-platform

# 2. 回滚代码
git checkout master
git pull origin master
git reset --hard $(git rev-parse origin/master)

# 3. 重启
systemctl start agent-platform || docker run -d --name agent-platform -p 8082:8082 agent-platform:latest
```

### 阶段 3：验证（4 分钟）
```bash
# 1. 健康检查
for i in {1..5}; do
    if curl -f http://localhost:8082/actuator/health; then
        break
    fi
    sleep 30
done

# 2. 核心业务验证
curl -X POST http://localhost:8082/api/rag/search \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: default" \
  -d '{"query":"你好"}' | jq .code

# 3. 解冻租户
curl -X POST http://localhost:8082/api/open/keys/enable-tenant \
  -H "Content-Type: application/json" -d '{"tenantId": "tenant-demo"}'
```

### 阶段 4：监控（2 分钟）
```bash
# 1. 启用健康检查监控
while true; do
    if ! curl -f http://localhost:8082/actuator/health; then
        echo "健康检查失败，执行紧急预案"
        ./scripts/紧急恢复.sh
        break
    fi
    sleep 60
done
```

---

## 4. 紧急预案

### 数据库级回滚
```bash
# 数据库快照恢复
pg_restore -h localhost -U postgres agent_platform_backup.dump -d agent_platform
```

### Docker 容器级回滚
```bash
# 切换回稳定镜像
docker tag agent-platform:stable agent-platform:latest
docker stop agent-platform
docker run -d --name agent-platform -p 8082:8082 agent-platform:latest
```

---

## 5. 演练记录

| 日期 | 操作人员 | 耗时 | 结果 | 改进项 |
|------|---------|------|------|--------|
| 2026-09-12 | 自动化 | 7分32秒 | ✅ 成功 | — |
| ... | ... | ... | ... | ... |

---

## 6. 自动化测试集成

```yaml
# .github/workflows/rollback-gate.yml
name: 回滚演练
on:
  schedule:
    - cron: '0 2 * * 1'  # 每周一凌晨2点
jobs:
  rollback:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: 执行回滚脚本
        run: |
          chmod +x scripts/rollback演练脚本.sh
          ./scripts/rollback演练脚本.sh
      - name: 验证功能
        run: |
          curl http://localhost:8082/actuator/health
          curl -X POST http://localhost:8082/api/rag/search -H "X-Tenant-Id: default" -d '{"query":"测试"}' | jq -e '.code == 0'
```

**目标：确保任何时候都能在 15 分钟内恢复服务。**