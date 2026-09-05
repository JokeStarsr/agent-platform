package com.agent.tool.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * L5 执行沙箱：代码沙箱（docs/design/security/20260905-sandbox.md §3）。
 * <p>Docker CLI 后端（跨 Windows/Linux/CI 可靠）：创建一次性容器（network none /
 * read-only rootfs / 内存&CPU&pids 限额 / cap-drop ALL / 非 root）→ exec 执行 →
 * 输出回传（上限截断）→ 超时强杀（exec destroy + docker kill）+ 容器删除。
 * 执行完销毁重建容器，杜绝跨租户残留污染。并发上限 = sandbox.pool-size。</p>
 */
@Service
public class CodeSandboxService {

    private static final Logger log = LoggerFactory.getLogger(CodeSandboxService.class);

    public record CodeResult(int exitCode, String stdout, String stderr,
                             long durationMs, String sandboxId, boolean timedOut) {
    }

    private final SandboxProperties props;
    private final Semaphore slots;
    private final AtomicInteger active = new AtomicInteger();

    public CodeSandboxService(SandboxProperties props) {
        this.props = props;
        this.slots = new Semaphore(Math.max(1, props.getPoolSize()));
    }

    @PostConstruct
    void init() {
        if (!dockerAvailable()) {
            log.warn("代码沙箱：Docker 不可达，exec 会失败（sandbox.docker-host/host 需可访问）");
        } else {
            ensureImage();
            log.info("代码沙箱就绪: image={} slots={}", props.getImage(), props.getPoolSize());
        }
    }

    @PreDestroy
    void close() {
        // CLI 后端无需常驻句柄
    }

    boolean dockerAvailable() {
        try {
            List<String> cmd = isWindows()
                    ? List.of("cmd.exe", "/c", "docker info >nul 2>&1")
                    : List.of("/bin/sh", "-c", "docker info >/dev/null 2>&1");
            Process p = new ProcessBuilder(cmd).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureImage() {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", props.getImage()).start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                return; // 镜像已存在
            }
        } catch (Exception e) {
            log.warn("检查镜像失败: {}", e.getMessage());
            return;
        }
        // 不存在 → 构建
        String dockerfile = props.getDockerfilePath();
        try {
            Process p = new ProcessBuilder("docker", "build", "-t", props.getImage(),
                    "-f", dockerfile, ".")
                    .directory(new java.io.File("."))
                    .start();
            readAll(p.getInputStream(), 64 * 1024);
            boolean done = p.waitFor(5, TimeUnit.MINUTES);
            if (done && p.exitValue() == 0) {
                log.info("沙箱镜像 {} 构建完成", props.getImage());
            } else {
                String err = readAll(p.getErrorStream(), 16 * 1024);
                log.warn("沙箱镜像构建失败: {}", err);
            }
        } catch (Exception e) {
            log.warn("沙箱镜像构建异常: {}", e.getMessage());
        }
    }

    /** 执行非信任代码：python3 / bash */
    public CodeResult execute(String language, String code, String stdin) {
        if (!acquireSlot()) {
            return new CodeResult(-1, "", "沙箱并发已满，请稍后重试", 0, "", false);
        }
        active.incrementAndGet();
        String sandboxId = "sbox-" + UUID.randomUUID().toString().substring(0, 8);
        String container = "sbx-" + UUID.randomUUID().toString().substring(0, 12);
        long start = System.currentTimeMillis();
        try {
            createContainer(container, sandboxId);
            startContainer(container);
            String[] execCmd = commandOf(language, code);
            return runExec(container, execCmd, start, sandboxId);
        } catch (RuntimeException e) {
            log.error("代码沙箱执行异常 sandbox={}: {}", sandboxId, e.getMessage());
            return new CodeResult(-1, "", "沙箱执行异常: " + e.getMessage(),
                    System.currentTimeMillis() - start, sandboxId, false);
        } finally {
            removeContainer(container);
            active.decrementAndGet();
            slots.release();
        }
    }

    private boolean acquireSlot() {
        try {
            if (!slots.tryAcquire(0)) {
                return slots.tryAcquire(props.getAcquireTimeoutSec(), TimeUnit.SECONDS);
            }
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void createContainer(String name, String sandboxId) {
        // network none / 只读 rootfs / 内存256m swap同值 / 1 CPU / pids64 / cap-drop ALL / 非root
        List<String> cmd = new ArrayList<>(List.of(
                "docker", "create",
                "--name", name,
                "--label", "agent-platform=sandbox",
                "--label", "sandbox-id=" + sandboxId,
                "--network", "none",
                "--read-only",
                "-m", props.getMemoryMb() + "m",
                "--memory-swap", props.getMemoryMb() + "m",
                "--cpus", String.valueOf(props.getCpus()),
                "--pids-limit", String.valueOf(props.getPidsLimit()),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--user", "10001:10001",
                "--tmpfs", "/tmp:rw,size=16m",
                "--workdir", "/home/runner",
                props.getImage(),
                "sh", "-c", "trap 'exit 0' TERM; sleep 3600"));
        run(cmd, 30);
    }

    private void startContainer(String name) {
        run(List.of("docker", "start", name), 30);
    }

    private CodeResult runExec(String container, String[] execCmd, long start, String sandboxId) {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", container));
        cmd.addAll(List.of(execCmd));
        Process p;
        try {
            p = new ProcessBuilder(cmd).start();
        } catch (Exception e) {
            return new CodeResult(-1, "", "exec 启动失败: " + e.getMessage(),
                    System.currentTimeMillis() - start, sandboxId, false);
        }
        StreamCollector stdout = new StreamCollector(p.getInputStream(), props.getOutputLimit());
        StreamCollector stderr = new StreamCollector(p.getErrorStream(), props.getOutputLimit());
        stdout.start();
        stderr.start();
        boolean timedOut = false;
        try {
            if (!p.waitFor(props.getExecTimeoutSec(), TimeUnit.SECONDS)) {
                timedOut = true;
                p.destroyForcibly();
                // 强杀容器（exec 残留不可留）
                try {
                    Process k = new ProcessBuilder("docker", "kill", "-s", "KILL", container).start();
                    k.waitFor(5, TimeUnit.SECONDS);
                } catch (Exception ignored) { }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            timedOut = true;
            p.destroyForcibly();
        }
        stdout.joinSafe();
        stderr.joinSafe();
        long durationMs = System.currentTimeMillis() - start;
        return new CodeResult(p.exitValue(), stdout.value(), stderr.value(), durationMs, sandboxId, timedOut);
    }

    private void removeContainer(String name) {
        try {
            Process p = new ProcessBuilder("docker", "rm", "-f", name).start();
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
    }

    private String[] commandOf(String language, String code) {
        if ("shell".equalsIgnoreCase(language)) {
            return new String[]{"sh", "-c", code};
        }
        return new String[]{"python3", "-u", "-c", code};
    }

    /* ---------- 工具 ---------- */

    private void run(List<String> cmd, int timeoutSec) {
        try {
            Process p = new ProcessBuilder(cmd).start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new RuntimeException("docker 命令超时: " + cmd.get(1));
            }
            if (p.exitValue() != 0) {
                String err = readAll(p.getErrorStream(), 8 * 1024);
                throw new RuntimeException("docker " + cmd.get(1) + " 失败: " + err.trim());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("docker 调用被中断");
        } catch (java.io.IOException e) {
            throw new RuntimeException("docker CLI 不可用: " + e.getMessage());
        }
    }

    private String readAll(java.io.InputStream in, int max) {
        try {
            byte[] buf = new byte[Math.min(max, 8192)];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 当前池状态（管理 API） */
    public Map<String, Object> health() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("slots", props.getPoolSize());
        m.put("active", active.get());
        m.put("image", props.getImage());
        m.put("docker", dockerAvailable() ? "connected" : "unreachable");
        return m;
    }

    /** 上限缓冲：超限截断并标记（防无限输出拖垮宿主） */
    static final class StreamCollector extends Thread {
        private final java.io.InputStream in;
        private final int limit;
        private final StringBuilder sb = new StringBuilder();
        private boolean truncated;

        StreamCollector(java.io.InputStream in, int limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public void run() {
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (!truncated) {
                        if (sb.length() + n > limit) {
                            sb.append(new String(buf, 0, Math.max(0, limit - sb.length())));
                            truncated = true;
                            sb.append("\n...[truncated]");
                        } else {
                            sb.append(new String(buf, 0, n));
                        }
                    }
                }
            } catch (Exception ignored) { }
        }

        void joinSafe() {
            try {
                join(Duration.ofSeconds(3).toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        String value() {
            return sb.toString();
        }
    }
}