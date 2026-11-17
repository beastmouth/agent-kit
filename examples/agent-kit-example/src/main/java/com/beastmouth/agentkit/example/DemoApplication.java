package com.beastmouth.agentkit.example;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例应用启动类。
 *
 * <p>启动前提（两条，缺一条起不来）：</p>
 * <ol>
 *   <li>MySQL 可连：会话与观测明细都要落库，建表脚本见
 *       {@code agent-kit-starter/src/main/resources/db/agent-kit-schema.sql}；</li>
 *   <li>一个模型渠道可用：默认走本机 Ollama，或把 {@code agent-kit.provider} 换成 deepseek
 *       并给 {@code DEEPSEEK_API_KEY}。</li>
 * </ol>
 *
 * <p>Redis / Milvus 不是必需：Redis 没有时会话缓存自动退化为直连 MySQL，
 * 记忆开关（{@code agent-kit.memory.enabled}）关掉时记忆相关调用返回空列表。</p>
 *
 * <p><b>为什么 {@code @MapperScan} 要手写四个包</b>：脚手架组件由 starter 的自动装配统一扫描，
 * 但 MyBatis 的 Mapper 不在组件扫描范围内。示例项目的启动类在 {@code ...agentkit.example} 下，
 * MyBatis 的默认扫描只覆盖启动类所在包，扫不到 {@code ...agentkit.obs.mapper} 这些兄弟包，
 * 结果是启动时 DataSource 就绪、一调会话就 NPE。真实项目里按同样方式列出脚手架的四个 mapper 包即可。</p>
 */
@SpringBootApplication
@MapperScan({
        "com.beastmouth.agentkit.obs.mapper",
        "com.beastmouth.agentkit.eval.mapper",
        "com.beastmouth.agentkit.memory.mapper",
        "com.beastmouth.agentkit.session.mapper"
})
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
