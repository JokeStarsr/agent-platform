package com.agent.orchestration.workflow;

import com.agent.common.BizException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * L3 编排层：内置流程定义目录（classpath:workflows/*.json）
 * v1 提供商旅主流程 trip_booking（Mock 工具版），供 start 复用定义。
 */
@Component
public class WorkflowFlows {

    /** 按名称读取内置流程定义 JSON */
    public String load(String name) {
        try {
            ClassPathResource res = new ClassPathResource("workflows/" + name + ".json");
            if (!res.exists()) {
                throw new BizException(404, "内置流程不存在: " + name);
            }
            return new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(500, "读取流程定义失败: " + name);
        }
    }
}
