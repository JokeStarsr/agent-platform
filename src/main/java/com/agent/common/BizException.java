package com.agent.common;

/**
 * 业务异常（配合全局异常处理器，业务代码禁止裸抛 RuntimeException）
 * 手写实现，避免 IDE 缺少 Lombok 注解处理器时编译失败
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = 1000;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}