package com.beastmouth.agentkit.starter;

import com.beastmouth.agentkit.agent.AgentResult;
import com.beastmouth.agentkit.agent.ChatEngine;
import com.beastmouth.agentkit.agent.ChatSink;
import com.beastmouth.agentkit.model.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎校验的边界：这是「配置对了但模块没引」唯一会给出人话错误的地方，值得钉住。
 *
 * <p>本模块的 classpath 上没有 {@code agent-kit-agentscope}，正好模拟真实场景。</p>
 */
class EngineGuardTest {

    @Test
    @DisplayName("配了 agentscope 却没引模块时报错并给出依赖片段")
    void agentscopeConfiguredButModuleMissing() {
        EngineGuard guard = guard(null, ChatEngine.AGENTSCOPE);
        IllegalStateException e = assertThrows(IllegalStateException.class, guard::afterSingletonsInstantiated);
        assertTrue(e.getMessage().contains("agent-kit-agentscope"), e.getMessage());
        assertTrue(e.getMessage().contains("<artifactId>"), "要能直接粘贴的依赖坐标");
    }

    @Test
    @DisplayName("引擎名写错时提示合法值（注意大小写不算错，Boot 条件匹配忽略大小写）")
    void illegalEngineValueReportsValidValues() {
        EngineGuard guard = guard(null, "agent_scope");
        IllegalStateException e = assertThrows(IllegalStateException.class, guard::afterSingletonsInstantiated);
        assertTrue(e.getMessage().contains("legacy") && e.getMessage().contains("agentscope"), e.getMessage());
    }

    @Test
    @DisplayName("默认不配时走 legacy")
    void defaultsToLegacy() {
        EngineGuard guard = guard(new FakeEngine(ChatEngine.LEGACY), null);
        assertDoesNotThrow(guard::afterSingletonsInstantiated);
    }

    @Test
    @DisplayName("配置与实际生效引擎不一致时报错")
    void mismatchBetweenConfigAndActualEngine() {
        EngineGuard guard = guard(new FakeEngine("someone-else"), ChatEngine.LEGACY);
        IllegalStateException e = assertThrows(IllegalStateException.class, guard::afterSingletonsInstantiated);
        assertTrue(e.getMessage().contains("someone-else"), e.getMessage());
    }

    @Test
    @DisplayName("legacy 生效且模块存在时只提醒不报错")
    void legacyWithModulePresentOnlyLogs() {
        // 本机 classpath 上没有 agentscope 模块，modulePresent 为 false，走的是静默分支
        EngineGuard guard = guard(new FakeEngine(ChatEngine.LEGACY), ChatEngine.LEGACY);
        assertDoesNotThrow(guard::afterSingletonsInstantiated);
    }

    private EngineGuard guard(ChatEngine engine, String configured) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (engine != null) {
            factory.addBean("chatEngine", engine);
        }
        ObjectProvider<ChatEngine> provider = factory.getBeanProvider(ChatEngine.class);
        MockEnvironment env = new MockEnvironment();
        if (configured != null) {
            env.setProperty(ChatEngine.ENGINE_PROPERTY, configured);
        }
        return new EngineGuard(provider, env);
    }

    /** 只需要 engineName()，其余方法用不到 */
    private record FakeEngine(String engineName) implements ChatEngine {
        @Override
        public AgentResult handle(List<ChatMessage> history, String userMessage, String lastAgentName,
                                 List<String> memories, ChatSink sink) {
            throw new UnsupportedOperationException();
        }
    }
}
