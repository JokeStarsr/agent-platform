package com.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * L5 工具协议层：工具参数 JSON Schema 轻量校验器
 * <p>平台工具 Schema 由自家工具声明（信任来源），仅需支持五个关键字：
 * type(object/string/number/integer/boolean)、properties、required、minimum/maximum（数值）。
 * 自研替代 everit（1.5.1 与运行时 org.json 类冲突），减少第三方依赖。</p>
 */
public final class ParamSchemaValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ParamSchemaValidator() {
    }

    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }

    /** 校验失败抛 ValidationException（message 携带首个错误描述，回给模型重试） */
    public static void validate(String schemaJson, Map<String, Object> args) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return;
        }
        Map<String, Object> schema;
        Map<String, Object> props;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> s = JSON.readValue(schemaJson, Map.class);
            schema = s;
            props = castMap(schema.get("properties"));
        } catch (Exception e) {
            throw new ValidationException("工具 Schema 格式非法: " + e.getMessage());
        }
        // required 检查（Schema 未声明 required 时跳过）
        List<?> required = castList(schema.get("required"));
        if (required != null) {
            for (Object r : required) {
                if (!args.containsKey(String.valueOf(r))) {
                    throw new ValidationException("缺少必需参数: " + r);
                }
            }
        }
        if (props == null) {
            return;
        }
        // 类型与范围检查
        for (Map.Entry<String, Object> e : props.entrySet()) {
            String name = e.getKey();
            Map<String, Object> def = castMap(e.getValue());
            if (def == null || !args.containsKey(name)) {
                continue;
            }
            Object val = args.get(name);
            String type = String.valueOf(def.getOrDefault("type", "string"));
            switch (type) {
                case "string" -> {
                    if (!(val instanceof String)) {
                        throw new ValidationException("参数 " + name + " 类型应为 string");
                    }
                }
                case "number", "integer" -> {
                    if (!(val instanceof Number n)) {
                        throw new ValidationException("参数 " + name + " 类型应为 " + type);
                    }
                    if (def.containsKey("minimum") && n.doubleValue() < ((Number) def.get("minimum")).doubleValue()) {
                        throw new ValidationException("参数 " + name + " 不得小于 " + def.get("minimum"));
                    }
                    if (def.containsKey("maximum") && n.doubleValue() > ((Number) def.get("maximum")).doubleValue()) {
                        throw new ValidationException("参数 " + name + " 不得大于 " + def.get("maximum"));
                    }
                }
                case "boolean" -> {
                    if (!(val instanceof Boolean)) {
                        throw new ValidationException("参数 " + name + " 类型应为 boolean");
                    }
                }
                default -> {
                    // 未知类型约束不拦截（宽松向前兼容）
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : null;
    }
}