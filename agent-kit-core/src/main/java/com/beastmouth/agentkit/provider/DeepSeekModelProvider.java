package com.beastmouth.agentkit.provider;

import com.beastmouth.agentkit.config.AiProperties;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 官方渠道：同样是 OpenAI 兼容协议，只是端点与模型名不同。
 *
 * <p>与硅基流动的差异只有三处，全部落在配置里：</p>
 * <ul>
 *   <li>{@code base-url} = {@code https://api.deepseek.com}（官方不带 /v1，带了也认）</li>
 *   <li>模型名是 <b>{@code deepseek-flash}</b>——控制台展示的版本号（如 DeepSeek-V4.1-Flash-1017）
 *       不是 API 的 model 取值，传错会直接 {@code invalid_request_error}</li>
 *   <li>思考开关走 {@code thinking:{type:disabled}}，由 {@code endpoints.deepseek.thinking} 控制</li>
 * </ul>
 */
@Component
public class DeepSeekModelProvider extends OpenAiCompatibleProvider {

    /** 与 {@code agent-kit.provider} 对应 */
    public static final String ID = "deepseek";

    public DeepSeekModelProvider(AiProperties properties) {
        super(properties, ID);
    }
}
