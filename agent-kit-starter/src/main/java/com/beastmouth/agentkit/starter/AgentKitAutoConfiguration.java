package com.beastmouth.agentkit.starter;

import com.beastmouth.agentkit.agent.ChatEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

/**
 * 引入即装配。
 *
 * <p>脚手架的所有组件都带 {@code @Component} / {@code @Service}，但没有一个
 * {@code @SpringBootApplication} 会扫描到 {@code com.beastmouth.agentkit}——
 * 业务应用的启动类通常在自己的包下。所以由自动装配统一注册一次组件扫描，
 * 使用方只需要在依赖里加上 {@code agent-kit-starter}。</p>
 *
 * <p><b>为什么整包扫描而不是逐个 {@code @Bean}</b>：模块会随版本增加组件（新 provider、
 * 新的观测出口、新的评测器），逐个手写装配意味着每次升级都要改这份清单，
 * 忘一个就是「引了新版但功能没生效」。包扫描让「新类 = 新能力」自动成立。</p>
 *
 * <p><b>引擎是按需引入的</b>：{@code agent-kit-core} 自带自研引擎（{@code legacy}），
 * {@code agent-kit-agentscope} 是独立模块、可选依赖。两者由 {@code agent-kit.engine}
 * 二选一，{@link EngineGuard} 负责在启动期把「配了引擎但没引模块」这类组合明确报出来。</p>
 *
 * <p>{@code agent-kit.enabled=false} 时整套不装配：留给「代码里有、某个环境关掉」的诉求，
 * 比改依赖坐标现实。</p>
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.beastmouth.agentkit")
@ConditionalOnProperty(prefix = "agent-kit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentKitAutoConfiguration {

    /**
     * 引擎校验必须显式注册：它不是 {@code @Component}（starter 里不放可被扫到的组件，
     * 否则关掉自动装配时它反而还在），只能在这里挂上去。
     */
    @Bean
    public EngineGuard engineGuard(ObjectProvider<ChatEngine> engines, Environment environment) {
        return new EngineGuard(engines, environment);
    }

    /**
     * 没有任何引擎时的兜底 Bean：唯一目的是把「没有 ChatEngine」换成能看懂的话。
     *
     * <p><b>为什么不能只靠 {@link EngineGuard}</b>：业务代码里只要有一处直接注入
     * {@code ChatEngine}（几乎所有接入方都会这样写），容器在创建那个 Bean 时就已经失败，
     * 报错是「required a bean of type ChatEngine that could not be found」——看不出是
     * 依赖没引、配置拼错，还是自动装配被关了。这里注册一个 {@code @ConditionalOnMissingBean}
     * 的 Bean，让容器拿到一个「会抛异常的实现」，异常正文就是人话。</p>
     *
     * <p>不返回 null、不返回空实现：那样会让应用「启动成功但每轮对话都炸」，
     * 比启动失败糟糕得多。</p>
     */
    @Bean
    @ConditionalOnMissingBean(ChatEngine.class)
    public ChatEngine missingChatEngine(Environment environment) {
        throw new IllegalStateException(EngineGuard.noEngineMessage(
                environment.getProperty(ChatEngine.ENGINE_PROPERTY, ChatEngine.LEGACY)));
    }
}
