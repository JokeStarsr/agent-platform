#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
W12 执行沙箱逃逸测试套件（红线，docs/design/security/20260905-sandbox.md §5）
================================
20 条攻击用例（网络/文件/资源/权限/进程/SQL）逐条打沙箱 API，
每条必须被拦截（exitCode!=0 / timedOut / 拒绝信息 / SQL 错误码）。
拦截率必须 = 100%：任何一条漏 = 沙箱不达标（本周只修沙箱）。

用法：
    1) 确保 agent-platform 已启动（沙箱配置：docker-host 可达、镜像已构建）
    2) python3 scripts/security/sandbox_escape_test.py
退出码：拦截率=100% → 0；否则 1。
"""

import json
import os
import sys
import urllib.request
import urllib.error

BASE_URL = os.getenv("SANDBOX_BASE_URL", "http://localhost:8082")

# 拒绝信号（出现任一 → 判定拦截成功）
DENY_MARKERS = [
    "exit", "Error", "error", "失败", "拒绝", "denied", "refused", "read-only",
    "no such", "no permission", "not permitted", "Operation not permitted",
    "permission denied", "timed out", "Timeout", "超时", "不可", "无法",
    "只允许", "仅允许", "解析失败", "沙箱",
]

def post(url, body):
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Tenant-Id": "default"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))

def run_code(code, language="python"):
    resp = post(f"{BASE_URL}/api/sandbox/code", {"language": language, "code": code, "stdin": ""})
    return resp.get("data") or {}

def run_sql(sql):
    resp = post(f"{BASE_URL}/api/sandbox/sql", {"sql": sql, "maxRows": 50})
    return resp

def blocked_text(stdout, stderr, exit_code, timed_out):
    if timed_out:
        return True
    if exit_code == 0:
        return False
    blob = (stdout or "") + "|" + (stderr or "")
    for m in DENY_MARKERS:
        if m.lower() in blob.lower():
            return True
    return exit_code != 0

CASES = [
    # ---- 网络类（5） ----
    ("code", "curl -s http://example.com", "python", "调用外部 HTTP"),
    ("code", "python -c \"import socket; socket.create_connection(('8.8.8.8', 53), 3)\"", "python", "TCP 出网"),
    ("code", "ping -c1 -W2 8.8.8.8", "shell", "ICMP 出网"),
    ("code", "python -c \"import urllib.request; urllib.request.urlopen('http://1.1.1.1', timeout=3)\"", "python", "urllib 出网"),
    ("code", "ssh -o ConnectTimeout=2 user@10.0.0.1", "shell", "SSH 出网"),
    # ---- 文件类（5） ----
    ("code", "cat /etc/shadow", "shell", "读敏感文件"),
    ("code", "python -c \"open('/proc/sys/kernel/hostname','w').write('h')\"", "python", "写宿主内核参数(proc)"),
    ("code", "python -c \"open('/root/pwn','w').write('x')\"", "python", "写 rootfs"),
    ("code", "dd if=/dev/zero of=/tmp/z bs=1M count=200", "shell", "磁盘写爆"),
    ("code", "cat /var/run/docker.sock", "shell", "访问 Docker socket"),
    # ---- 资源类（4） ----
    ("code", ":(){ :|:& };:", "shell", "fork 炸弹"),
    ("code", "python -c \"x=[0]*10**9\"", "python", "内存耗尽"),
    ("code", "while true; do :; done", "shell", "CPU 死循环"),
    ("code", "dd if=/dev/zero of=/dev/full bs=1M count=1", "shell", "写满 /tmp 溢出"),
    # ---- 权限类（3） ----
    ("code", "sudo /bin/bash -c id", "shell", "特权提升"),
    ("code", "python -c \"import os; os.setuid(0); print('root')\"", "python", "setuid 提权"),
    ("code", "setcap cap_net_raw=ep /bin/sh && /bin/sh -c id", "shell", "capset 提权"),
    # ---- 进程类（1） ----
    ("code", "nsenter -t 1 --all true", "shell", "进入宿主命名空间"),
    # ---- SQL 注入（2） ----
    ("sql", "SELECT * FROM t_app; DROP TABLE t_app", None, "SQL 多语句注入"),
    ("sql", "UPDATE t_tool_grant SET enabled=true WHERE tool_name='book_order'", None, "SQL 写操作注入"),
]

def main():
    total = 0
    blocked = 0
    print("=" * 60)
    print("W12 沙箱逃逸测试（红线：拦截率必达 100%）")
    print("=" * 60)
    for i, case in enumerate(CASES, 1):
        kind, payload, lang, desc = case
        ok = False
        detail = ""
        try:
            if kind == "code":
                r = run_code(payload, lang or "python")
                ok = blocked_text(r.get("stdout", ""), r.get("stderr", ""),
                                  r.get("exitCode", -1), r.get("timedOut", False))
                detail = f"exit={r.get('exitCode')} timedOut={r.get('timedOut')} out={r.get('stdout', '')[:40]!r}"
            else:
                resp = run_sql(payload)
                code = resp.get("code", -1)
                ok = code != 0
                detail = f"code={code} msg={resp.get('message', '')[:50]!r}"
            total += 1
            if ok:
                blocked += 1
                flag = "BLOCKED"
            else:
                flag = "!! LEAK !!"
        except Exception as e:
            total += 1
            flag = "BLOCKED(api err)"
            blocked += 1
            detail = f"{e}"
        print(f"[{i:>2}] {flag:<16} {desc}: {detail}")

    rate = blocked / total if total else 0.0
    print("-" * 60)
    print(f"拦截率: {blocked}/{total} = {rate:.1%}（要求 100%）")
    if rate < 1.0:
        print("FAIL：存在逃逸用例未被拦截，沙箱不达标。")
        sys.exit(1)
    print("PASS：全部逃逸用例已拦截。")
    sys.exit(0)

if __name__ == "__main__":
    main()