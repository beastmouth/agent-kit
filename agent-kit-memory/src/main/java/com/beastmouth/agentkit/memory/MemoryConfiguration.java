package com.beastmouth.agentkit.memory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 打开记忆模块的配置绑定。
 *
 * <p><b>为什么单独一个类，而不是给 {@link MemoryProperties} 加 {@code @Component}</b>：
 * 配置类由本包扫描发现，记忆模块不在 classpath 时这个类根本不会被加载，
 * 不会出现「引用了不存在的类」的启动失败。放在 starter 里做 {@code @EnableConfigurationProperties}
 * 反而会把 starter 与 memory 焊死——而 memory 在 starter 里是 optional 的。</p>
 *
 * <p>这个类曾经是漏的：{@code MemoryProperties} 只有 {@code @ConfigurationProperties}
 * 注解，没有任何地方把它注册成 Bean，表现为「引入 memory 模块后启动即失败，
 * 报 No qualifying bean of type MemoryProperties」。症状看起来像业务配置写错，
 * 实际是模块自己没装配。</p>
 */
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfiguration {
}
