package com.beastmouth.agentkit.agent;

import com.beastmouth.agentkit.model.ChatMessage;

import java.util.List;

/**
 * 对话引擎的对外入口。
 *
 * <p>存在的理由只有一个：让「自研薄运行时」与「AgentScope 引擎」可以并存，
 * 由 {@code agent-kit.engine} 在启动时二选一。上层（控制器、IM 机器人、
 * 黄金集回放）只认这个接口，不认具体实现——换引擎时这三处一行都不用改。</p>
 *
 * <p><b>契约</b>：返回的 {@link AgentResult} 语义不变（一句话结论 + 动作序列），
 * 流式事件仍通过 {@link ChatSink} 的三类回调输出。任何实现都不得改变这两个约定，
 * 否则前端 SSE 帧与质量规则会同时失效。</p>
 */
public interface ChatEngine {

    /**
     * 处理一轮对话。
     *
     * @param history       会话历史（已按时间正序，不含本轮用户消息）
     * @param userMessage   本轮用户原话
     * @param lastAgentName 上一轮承接的子 Agent 名，用于「继续/还有呢」这类指代消解；可为 null
     * @param memories      与本轮输入相关的长期记忆行文本（来自 AgentMemoryService#recall），可为空列表
     * @param sink          流式输出回调，不允许为 null（无流式需求传 {@link ChatSink#NOOP}）
     */
    AgentResult handle(List<ChatMessage> history, String userMessage, String lastAgentName,
                       List<String> memories, ChatSink sink);

    /** 便捷重载：不关心上一轮子 Agent 与长期记忆时使用 */
    default AgentResult handle(List<ChatMessage> history, String userMessage, ChatSink sink) {
        return handle(history, userMessage, null, List.of(), sink);
    }

    /** 便捷重载：只带上一轮子 Agent 名 */
    default AgentResult handle(List<ChatMessage> history, String userMessage, String lastAgentName, ChatSink sink) {
        return handle(history, userMessage, lastAgentName, List.of(), sink);
    }

    /** 引擎标识，用于日志、健康检查与黄金集报告区分来源 */
    String engineName();
}
