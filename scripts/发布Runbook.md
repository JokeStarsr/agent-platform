# Agent Platform 发布 Runbook（冲刺4，线上发布指南）

> 版本：v1.0 ｜ 状态：**已测试** ｜ 停机窗口：每日 02:00-03:00（低峰期）

---

## 1. 发布准备清单

### 前置条件
- [ ] 所有测试通过（227/227）
- [ ] 回滚演练成功（上次 ≤15 分钟恢复）
- [ ] 数据库备份完成（最新备份 t_backup_log）
- [ ] 变更清单评审完成
- [ ] 监控平台就绪（Prometheus + 日志聚合）

### 环境要求
```yaml
目标服务器: 10.0.1.100:8082
数据库:    10.0.1.200:5432
Redis:     10.0.1.200:6379
镜像仓库:  harbor.jokestars.com/agent-platform
备份存储:  /backup/agent-platform/
```

---

## 2. 发布流程

### 第 1 步：变更锁定（1 分钟）
```bash
# 1. 通知变更窗口
echo "发布窗口开始：$(date)" | mail -s "Agent Platform 发布通知" ops@example.com

# 2. 冻结 API Key 临时限制
curl -X POST http://localhost:8082/api/open/keys/rate-limit-set \
  -H "Content-Type: application/json" -d '{"limit": 10}'
```

### 第 2 步：版本构建（3 分钟）
```bash
# 1. 拉取最新代码
git pull origin master
git rev-list --count HEAD

# 2. 本地编译测试
mvn clean compile -q
mvn test -Dtest=ArchitectureTest,TenantIsolationTest -q

# 3. 打包镜像
docker build -t harbor.jokestars.com/agent-platform:v$(date +%Y%m%d) .
docker push harbor.jokestars.com/agent-platform:v$(date +%Y%m%d)
```

### 第 3 步：灰度发布（15 分钟）

#### 方案 A：滚动更新（推荐）
```bash
# 1. 蓝绿部署新容器
docker run -d --name agent-platform-blue harbor.jokestars.com/agent-platform:v$(date +%Y%m%d)

# 2. 健康检查
for i in {1..10}; do
    if curl -f http://agent-platform-blue:8082/actuator/health; then
        break
    fi
    sleep 10
done

# 3. 切换流量（负载均衡器）
curl -X POST http://lb01.example.com/v1/servers/agent-platform/targets \
  -H "Content-Type: application/json" -d '{"server": "agent-platform-blue"}'

# 4. 停止旧容器
docker stop agent-platform-green
```

#### 方案 B：Kubernetes 部署
```bash
# 1. 部署新版本
kubectl apply -f deploy/agent-platform-v$(date +%Y%m%d).yaml

# 2. 等待就绪
kubectl rollout status deployment/agent-platform

# 3. 回滚旧版本
kubectl scale deployment agent-platform --replicas=0
```

### 第 4 步：验证（5 分钟）
```bash
# 1. 全功能测试
./scripts/发布验证脚本.sh

# 2. 核心监控检查
curl http://localhost:8082/actuator/prometheus | grep -E "http_server_requests_seconds_count"

# 3. 健康检查
curl -f http://localhost:8082/actuator/health
curl -f http://localhost:8082/actuator/health/liveness
curl -f http://localhost:8082/actuator/health/readiness
```

### 第 5 步：清理（1 分钟）
```bash
# 1. 清理旧镜像
docker rmi harbor.jokestars.com/agent-platform:v$(date +%Y%m%d --date="-7 days") || true

# 2. 通知完成
echo "发布完成：$(date)" | mail -s "Agent Platform 发布通知" ops@example.com
```

---

## 3. 监控指标

### 关键指标（发布后 30 分钟）
| 指标 | 目标 | 告警阈值 |
|------|------|----------|
| http_server_requests_seconds_p95 | <1s | >2s |
| jvm_memory_used_percent | <80% | >90% |
| error_rate | <0.1% | >1% |
| active_users | 稳定 | -20% |

### 告警通知
```yaml
alerting_rules:
  - name: 503错误率
    condition: rate(http_server_requests{status=~"5.."}[5m]) > 0.01
    actions: ["邮件通知", "钉钉群机器人"]
  - name: JVM内存泄漏
    condition: jvm_memory_used_percent > 90
    actions: ["短信通知", "电话告警"]
```

---

## 4. 回滚触发条件

### 自动回滚（持续监控）
```bash
# 健康检查失败自动回滚
if ! curl -f http://localhost:8082/actuator/health; then
    echo "健康检查失败，自动回滚"
    ./scripts/rollback演练脚本.sh
    exit 1
fi
```

### 手动回滚清单
- [ ] 503 错误率 > 5%（5分钟内）
- [ ] 核心功能不可用（如支付/登录）
- [ ] JVM 内存持续增长
- [ ] 响应延迟 > 2 分钟

---

## 5. 事后处理

### 发布报告
```markdown
# Agent Platform 发布报告

**版本**：v$(date +%Y%m%d)
**时间**：$(date)
**发布方式**：滚动更新

## 变更内容
- 新增：删除级联服务
- 新增：注入检测管道
- 优化：缓存命中率提升

## 发布结果
- 健康检查：✅
- 功能测试：✅
- 性能指标：✅

## 监控数据
- 平均响应：320ms
- P95 延迟：890ms
- 内存使用：62%
```

### 演习改进
```yaml
# 发布后分析
issues_discovered:
  - "健康检查偶现超时，增大超时到 60s"
  - "日志收集延迟，优化 Fluentd 配置"

next_improvements:
  - 增加发布前自动化验证
  - 完善蓝绿部署脚本
  - 增加数据库回滚步骤
```

---

**发布原则：宁可回滚，不可冒险。任何时候都能 ≤15 分钟恢复。**