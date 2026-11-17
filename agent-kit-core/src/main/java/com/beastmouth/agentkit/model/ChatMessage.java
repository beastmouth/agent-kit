package com.beastmouth.agentkit.model;

/**
 * 送给模型的一条消息。
 *
 * <p>只保留 role + content 两个字段：当前所有提供方（Ollama / OpenAI 兼容）都接受这种最简结构，
 * 工具调用通过提示词里的 JSON 协议表达，不依赖各家不同的 {@code tool_calls} 报文，
 * 换模型不用改协议。</p>
 *
 * @param role    system / user / assistant
 * @param content 文本内容
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
