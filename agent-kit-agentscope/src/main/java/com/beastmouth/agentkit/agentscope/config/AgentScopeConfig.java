package com.beastmouth.agentkit.agentscope.config;

import com.beastmouth.agentkit.agent.AgentRegistry;
import com.beastmouth.agentkit.agent.ChatEngine;
import com.beastmouth.agentkit.agent.IntentRouter;
import com.beastmouth.agentkit.agentscope.engine.AgentScopeEngine;
import com.beastmouth.agentkit.agentscope.obs.RuntimeObs;
import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.provider.ModelRouter;
import com.beastmouth.agentkit.tool.ToolInterceptor;
import com.beastmouth.agentkit.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * AgentScope 引擎装配。
 *
 * <p>只在 {@code agent-kit.engine=agentscope} 时生效。默认 {@code legacy} 走
 * {@code MainAgent}（它自带 {@code matchIfMissing=true}），两套实现互斥，
 * 不会出现「两个 ChatEngine 抢注入」的歧义。</p>
 *
 * <p>刻意不引 {@code agentscope-spring-boot-starter}：它依赖 {@code spring-boot-autoconfigure 4.0.1}，
 * 与 Boot 4.1 的模块化自动配置冲突。这里手工装配，只用到框架的运行时能力，不碰它的自动配置。</p>
 *
 * <p><b>为什么是 {@code @Configuration} 而不是 {@code @AutoConfiguration}</b>：
 * starter 的 {@code AgentKitAutoConfiguration} 已经整包扫描 {@code com.beastmouth.agentkit}，
 * 本类会被它扫到。再加一份 {@code AutoConfiguration.imports} 等于同一份配置有两个来源，
 * 排查「为什么 Bean 注册了两次」时很难意识到是这里。与 core 里其余组件保持同一种注册方式。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AgentScopeProperties.class)
@ConditionalOnProperty(name = ChatEngine.ENGINE_PROPERTY, havingValue = ChatEngine.AGENTSCOPE)
public class AgentScopeConfig {

    public AgentScopeConfig() {
        log.info("AgentScopeConfig#init#Agent 引擎切换为 agentscope");
    }

    @Bean
    public ChatEngine agentScopeChatEngine(AiProperties aiProperties,
                                           ToolRegistry toolRegistry,
                                           AgentRegistry agentRegistry,
                                           IntentRouter intentRouter,
                                           ModelRouter modelRouter,
                                           RuntimeObs runtimeObs,
                                           AgentScopeProperties scopeProperties,
                                           List<ToolInterceptor> toolInterceptors) {
        return new AgentScopeEngine(aiProperties, toolRegistry, agentRegistry, intentRouter,
                modelRouter, runtimeObs, scopeProperties, toolInterceptors);
    }
}
