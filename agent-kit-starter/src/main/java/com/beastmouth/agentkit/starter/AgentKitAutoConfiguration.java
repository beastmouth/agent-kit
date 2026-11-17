package com.beastmouth.agentkit.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

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
 * <p>{@code agent-kit.enabled=false} 时整套不装配：留给「代码里有、某个环境关掉」的诉求，
 * 比改依赖坐标现实。</p>
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.beastmouth.agentkit")
@ConditionalOnProperty(prefix = "agent-kit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentKitAutoConfiguration {
}
