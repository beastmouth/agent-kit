package com.beastmouth.agentkit.provider;

import com.beastmouth.agentkit.config.AiProperties;

/**
 * 由配置声明的自定义渠道：不写任何 Java 类，在 {@code agent-kit.endpoints.<id>} 里给出
 * {@code base-url} / {@code model} / {@code api-key} 即可生效。
 *
 * <p>与 {@link DeepSeekModelProvider}、{@link SiliconFlowModelProvider} 不同，本类<b>没有
 * 固定的 id</b>——每个实例的 id 来自配置键名（由 {@link ModelProviderRegistry} 在启动期
 * 扫描 endpoints 时动态创建）。配 {@code endpoints.custom} 就得到 {@code custom} 渠道，
 * 配 {@code endpoints.company-gateway} 就得到 {@code company-gateway} 渠道，可以同时存在
 * 任意多个，都能进 {@code agent-kit.routes} 参与业务分流。</p>
 *
 * <p>协议限制：只支持 OpenAI 兼容协议（{@code POST {base-url}/chat/completions}）。
 * Ollama 的 {@code /api/chat} 协议不同，本地模型仍用内置 {@code ollama} 渠道。</p>
 */
public class CustomModelProvider extends OpenAiCompatibleProvider {

    public CustomModelProvider(AiProperties properties, String id) {
        super(properties, id);
    }
}
