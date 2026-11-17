package com.beastmouth.agentkit.tool;

import java.util.Map;
import java.util.function.Function;

/**
 * 用 lambda 定义的工具，省掉为每个动作单独建类。
 *
 * @param name        工具名
 * @param description 能力说明
 * @param schema      参数 JSON Schema
 * @param handler     执行逻辑
 */
public record FunctionTool(String name, String description, String schema,
                           Function<Map<String, Object>, AgentTool.ToolOutcome> handler) implements AgentTool {

    @Override
    public String parametersSchema() {
        return schema;
    }

    @Override
    public AgentTool.ToolOutcome execute(Map<String, Object> args) {
        return handler.apply(args);
    }
}
