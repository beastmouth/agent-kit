package com.beastmouth.agentkit.provider;

import com.beastmouth.agentkit.config.AiProperties;
import org.springframework.stereotype.Component;

/**
 * 硅基流动（SiliconFlow）渠道：OpenAI 兼容协议，端点见 {@code agent-kit.endpoints.siliconflow.*}。
 *
 * <p>协议细节全在 {@link OpenAiCompatibleProvider}，这里只声明渠道 id，
 * 与配置里的 {@code agent-kit.provider} 取值对齐。</p>
 */
@Component
public class SiliconFlowModelProvider extends OpenAiCompatibleProvider {

    /** 与 {@code agent-kit.provider} 对应 */
    public static final String ID = "siliconflow";

    public SiliconFlowModelProvider(AiProperties properties) {
        super(properties, ID);
    }
}
