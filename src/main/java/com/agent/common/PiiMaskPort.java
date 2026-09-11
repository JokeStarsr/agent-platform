package com.agent.common;

/**
 * 跨层接口：PII 脱敏端口（冲刺 1）
 * <p>common 是基础设施层，不 import 具体业务实现；由 capability.security.PiiMasker 实现本接口，
 * AuditLogAspect 经 ObjectProvider 按需注入，实现"日志自动掩码"且不破坏七层依赖。</p>
 */
public interface PiiMaskPort {

    /** 对文本做 PII 脱敏（身份证/银行卡/手机/邮箱/密钥 → ***），返回脱敏后文本 */
    String maskSensitive(String text);

    /** 判断是否包含 PII */
    boolean containsPii(String text);
}