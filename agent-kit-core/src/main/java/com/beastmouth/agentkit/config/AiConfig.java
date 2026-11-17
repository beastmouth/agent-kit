package com.beastmouth.agentkit.config;

import com.beastmouth.agentkit.eval.EvalProperties;
import com.beastmouth.agentkit.obs.ObsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 打开配置绑定。
 *
 * <p>只做一件事：把 {@code agent-kit.*}、{@code agent-kit.obs.*}、{@code agent-kit.eval.*}
 * 与 {@code agent-kit.router.*} 绑定成对应的配置类。
 * 模型提供方、工具、子 Agent 都由组件扫描自动装配，这里不做手工注册。</p>
 *
 * <p>显式声明是为了让「只引 jar 不做组件扫描」的用法（如
 * {@code @SpringBootApplication(scanBasePackages=...)} 排除了本包）也能拿到配置——
 * 绑定失败的表现是运行期 NPE，排查成本远高于这一行。</p>
 */
@Configuration
@EnableConfigurationProperties({AiProperties.class, ObsProperties.class, EvalProperties.class,
        RouterProperties.class})
public class AiConfig {
}
