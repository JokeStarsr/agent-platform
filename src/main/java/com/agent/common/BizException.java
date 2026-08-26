package com.agent.common;

import lombok.Getter;

/**
 * 业务异常（配合全局异常处理器，业务代码禁止裸抛 RuntimeException）
 */
@Getter
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
}