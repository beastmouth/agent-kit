package com.beastmouth.agentkit.provider;

import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.model.ModelReply;

import java.util.List;
import java.util.function.Consumer;

/**
 * 大模型提供方抽象。
 *
 * <p>新增提供方（DeepSeek、通义、vLLM、OpenAI…）只需实现本接口并注册为 Spring Bean，
 * 运行时按 {@code agent-kit.provider} 选择，业务代码不感知具体厂商。</p>
 */
public interface ModelProvider {

    /** 提供方标识，与配置项 {@code agent-kit.provider} 对应 */
    String id();

    /**
     * 同步对话。
     *
     * @param messages 完整上下文
     * @param jsonMode 是否要求模型输出严格 JSON（结构化工具调用协议依赖它）
     */
    ModelReply chat(List<ChatMessage> messages, boolean jsonMode);

    /**
     * 流式对话，边生成边回调增量文本。
     *
     * @param onDelta 每个增量片段回调一次，可为 null
     * @return 聚合后的完整结果
     */
    ModelReply stream(List<ChatMessage> messages, Consumer<String> onDelta);

    /**
     * 该渠道当前是否应该走流式（{@code agent-kit.stream} 与 {@code endpoints.<id>.stream} 共同决定）。
     *
     * <p>由调用点据此决定调 {@link #stream} 还是 {@link #chat}，而不是让 {@link #stream} 内部
     * 偷偷降级成同步——那样观测里的 {@code ai_llm_call.stream} 列会一直记 1，看报表的人
     * 永远发现不了「这个渠道其实没在流」。</p>
     */
    default boolean streaming() {
        return true;
    }
}
