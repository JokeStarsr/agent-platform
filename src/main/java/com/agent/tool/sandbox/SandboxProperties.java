package com.agent.tool.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * L5 执行沙箱配置（agent-platform.sandbox.*，docs/design/security/20260905-sandbox.md §3）
 */
@ConfigurationProperties(prefix = "agent-platform.sandbox")
public class SandboxProperties {

    /** Docker 连接（Windows 默认 npipe；Linux 需 unix:///var/run/docker.sock；CI 用 tcp://host:2375） */
    private String dockerHost = "";

    /** Docker TLS 证书路径（默认空=不启用 TLS） */
    private String dockerTlsVerify = "";

    /** 沙箱镜像 tag（代码执行） */
    private String image = "sboxes/python-311";

    /** 镜像不存在时自动 build 的 Dockerfile 路径（文件系统；空=跳过 build） */
    private String dockerfilePath = "docker/sandbox/Dockerfile.python";

    /** 池大小 / 并发上限 */
    private int poolSize = 2;

    /** 内存限额（MB） */
    private long memoryMb = 256;

    /** CPU 限额（核数） */
    private double cpus = 1.0;

    /** pids 上限（防 fork 炸弹） */
    private long pidsLimit = 64;

    /** 执行超时（秒），超时强杀容器 */
    private int execTimeoutSec = 8;

    /** 取容器等待上限（秒），超限抛 429 */
    private int acquireTimeoutSec = 3;

    /** stdout/stderr 回传上限（字节），超过截断 */
    private int outputLimit = 16 * 1024;

    /** SQL 沙箱：只读账号 JDBC URL（默认同业务库，只读角色） */
    private String sqlJdbcUrl = "";

    /** SQL 沙箱：只读账号 */
    private String sqlUser = "sandbox_ro";

    /** SQL 沙箱：只读账号密码 */
    private String sqlPassword = "";

    /** 强制 LIMIT / 结果行上限 */
    private int sqlMaxRows = 100;

    /** SQL 时间限制（秒） */
    private int sqlTimeoutSec = 10;

    public String getDockerHost() { return dockerHost; }
    public void setDockerHost(String dockerHost) { this.dockerHost = dockerHost; }
    public String getDockerTlsVerify() { return dockerTlsVerify; }
    public void setDockerTlsVerify(String dockerTlsVerify) { this.dockerTlsVerify = dockerTlsVerify; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getDockerfilePath() { return dockerfilePath; }
    public void setDockerfilePath(String dockerfilePath) { this.dockerfilePath = dockerfilePath; }
    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
    public long getMemoryMb() { return memoryMb; }
    public void setMemoryMb(long memoryMb) { this.memoryMb = memoryMb; }
    public double getCpus() { return cpus; }
    public void setCpus(double cpus) { this.cpus = cpus; }
    public long getPidsLimit() { return pidsLimit; }
    public void setPidsLimit(long pidsLimit) { this.pidsLimit = pidsLimit; }
    public int getExecTimeoutSec() { return execTimeoutSec; }
    public void setExecTimeoutSec(int execTimeoutSec) { this.execTimeoutSec = execTimeoutSec; }
    public int getAcquireTimeoutSec() { return acquireTimeoutSec; }
    public void setAcquireTimeoutSec(int acquireTimeoutSec) { this.acquireTimeoutSec = acquireTimeoutSec; }
    public int getOutputLimit() { return outputLimit; }
    public void setOutputLimit(int outputLimit) { this.outputLimit = outputLimit; }
    public String getSqlJdbcUrl() { return sqlJdbcUrl; }
    public void setSqlJdbcUrl(String sqlJdbcUrl) { this.sqlJdbcUrl = sqlJdbcUrl; }
    public String getSqlUser() { return sqlUser; }
    public void setSqlUser(String sqlUser) { this.sqlUser = sqlUser; }
    public String getSqlPassword() { return sqlPassword; }
    public void setSqlPassword(String sqlPassword) { this.sqlPassword = sqlPassword; }
    public int getSqlMaxRows() { return sqlMaxRows; }
    public void setSqlMaxRows(int sqlMaxRows) { this.sqlMaxRows = sqlMaxRows; }
    public int getSqlTimeoutSec() { return sqlTimeoutSec; }
    public void setSqlTimeoutSec(int sqlTimeoutSec) { this.sqlTimeoutSec = sqlTimeoutSec; }
}