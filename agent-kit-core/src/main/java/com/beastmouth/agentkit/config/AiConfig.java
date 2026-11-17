package com.beastmouth.agentkit.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 打开配置绑定。
 *
 * <p>只做一件事：把 {@code agent-kit.*} 与 {@code agent-kit.router.*} 绑定成
 * {@link AiProperties} / {@link RouterProperties}。模型提供方、工具、子 Agent
 * 都由组件扫描自动装配，这里不做手工注册。</p>
 *
 * <h3>只列「没有 @Component 的两个」，不是漏写</h3>
 * <p>{@code ObsProperties} 与 {@code EvalProperties} 自带 {@code @Component}，已经由组件扫描
 * 注册过了。如果这里再 {@code @EnableConfigurationProperties} 一次，容器里会出现<b>两个</b>
 * 同类型 Bean（bean 名分别是 {@code obsProperties} 与 {@code agent-kit.obs-...ObsProperties}），
 * 按类型注入的地方直接报 {@code required a single bean, but 2 were found}——
 * 这个错误看起来像"配置写重了"，实际是装配重复，2026-11-17 启动示例时踩过一次。</p>
 *
 * <p><b>新增配置类时的判断</b>：类上有 {@code @Component} 就别往这里加；
 * 没有就加进来。二者只取一个，永远不要同时用两种方式注册同一个类。</p>
 */
@Configuration
@EnableConfigurationProperties({AiProperties.class, RouterProperties.class})
public class AiConfig {
}
