package com.beastmouth.agentkit.model;

/**
 * 一个可被模型调用的工具描述。
 *
 * @param name        工具名，模型按它回传，全局唯一
 * @param description 给模型看的能力说明，要写清「什么时候用」
 * @param parameters  参数的 JSON Schema（序列化后嵌进提示词）
 */
public record ToolSpec(String name, String description, String parameters) {

    /** 渲染成提示词里的一行，例如 {@code - order_query: 按条件查询订单 ...} */
    public String toPromptLine() {
        return "- " + name + ": " + description + "\n  参数: " + parameters;
    }
}
