package com.beastmouth.agentkit.starter;

import com.beastmouth.agentkit.agent.ChatEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * 启动期校验「配的引擎」与「装没装那个模块」是否对得上。
 *
 * <p>存在理由：引擎选择是<b>配置项 + 依赖</b>两件事的组合，缺一个都会以很难排查的方式失败。
 * 四种组合里有两种是错的，但 Spring 自己报出来的错完全看不出根因：</p>
 *
 * <ul>
 *   <li>{@code engine=agentscope} 但 pom 里没加 {@code agent-kit-agentscope} →
 *       容器里一个 {@link ChatEngine} 都没有，报的是「某处注入 ChatEngine 失败」，
 *       而真正的原因在依赖坐标上；</li>
 *   <li>{@code engine=agent_scope}（拼成别的词）→ 两个实现的条件都不满足，同样是「没有 Bean」。
 *       注意 Boot 的条件匹配是<b>忽略大小写</b>的，所以 {@code agentScope} 能正常生效，不算错；</li>
 * </ul>
 *
 * <p>所以这里把「容器里有没有引擎」「有没有对应模块的类」「配置值合不合法」三件事
 * 在启动阶段一次性讲清楚，并直接给出可粘贴的依赖片段。宁可启动失败，
 * 也不要带着一个「引了 AgentScope 却在跑 legacy」的假象上线。</p>
 *
 * <p>另一种组合是合法但值得提醒的：{@code engine=legacy} 而 classpath 上有 AgentScope 模块。
 * 这时不报错，只打一条 INFO——它常见于「先加依赖、配置还没改」的中间状态，
 * 不提醒的话使用者会以为已经在跑 AgentScope 了。</p>
 */
@Slf4j
public class EngineGuard implements SmartInitializingSingleton {

    /** 用于判断模块在不在 classpath 上，不能直接 import（否则模块缺失时本类编译不过） */
    private static final String AGENTSCOPE_ENGINE = "com.beastmouth.agentkit.agentscope.engine.AgentScopeEngine";

    private static final String ADD_DEPENDENCY = """
            <dependency>
                <groupId>com.beastmouth.kit</groupId>
                <artifactId>agent-kit-agentscope</artifactId>
                <version>${agent-kit.version}</version>
            </dependency>""";

    private final ObjectProvider<ChatEngine> engines;
    private final Environment environment;

    public EngineGuard(ObjectProvider<ChatEngine> engines, Environment environment) {
        this.engines = engines;
        this.environment = environment;
    }

    /**
     * 在所有单例实例化之后、容器启动完成之前执行：此时该注册的 ChatEngine 一定已经在了，
     * 又不会因为提前取 Bean 打乱 bean 的创建顺序（DataSource / Mapper 尤其敏感）。
     */
    @Override
    public void afterSingletonsInstantiated() {
        String configured = environment.getProperty(ChatEngine.ENGINE_PROPERTY, ChatEngine.LEGACY);
        ChatEngine engine = engines.getIfAvailable();
        if (engine == null) {
            throw new IllegalStateException(noEngineMessage(configured));
        }
        if (!engine.engineName().equalsIgnoreCase(configured)) {
            // 理论上走不到：两个实现的条件互斥且都认 configValue。真出现了说明有人自己注册了 ChatEngine
            // （测试替身、二次封装），这时报错比「悄悄跑另一个引擎」安全。
            throw new IllegalStateException("""
                    配置 %s=%s，但容器里生效的引擎是 %s。agent-kit 只允许一个 ChatEngine，\
                    请检查是否手动注册了 ChatEngine Bean，或把配置改成 %s。"""
                    .formatted(ChatEngine.ENGINE_PROPERTY, configured, engine.engineName(), engine.engineName()));
        }
        boolean modulePresent = ClassUtils.isPresent(AGENTSCOPE_ENGINE, getClass().getClassLoader());
        if (ChatEngine.LEGACY.equalsIgnoreCase(configured) && modulePresent) {
            log.info("EngineGuard#check#classpath 上有 agent-kit-agentscope，但 {}=legacy 生效中；"
                    + "要切到 AgentScope 请改为 {}=agentscope", ChatEngine.ENGINE_PROPERTY, ChatEngine.ENGINE_PROPERTY);
        }
        log.info("EngineGuard#check#Agent 引擎 = {}（{}），AgentScope 模块{}",
                engine.engineName(), engine.getClass().getSimpleName(), modulePresent ? "在 classpath 上" : "未引入");
    }

    /**
     * 「一个引擎都没有」的报错文案。
     *
     * <p>public static 是刻意的：这条信息还要被 {@code AgentKitAutoConfiguration} 的兜底 Bean
     * 复用。实际生效的往往是兜底 Bean——业务代码里只要有一处直接注入 {@code ChatEngine}，
     * 容器在创建那个 Bean 时就失败了，轮不到本类的 {@code afterSingletonsInstantiated}。
     * 两处共用同一份文案，避免「报错说的和文档写的不是一回事」。</p>
     */
    static String noEngineMessage(String configured) {
        String head;
        String advise;
        if (ChatEngine.AGENTSCOPE.equalsIgnoreCase(configured)) {
            // 值写对了但模块没引：99% 是这里
            head = "配置 %s=agentscope，但 classpath 上没有 agent-kit-agentscope 模块，容器里没有可用的 ChatEngine。"
                    .formatted(ChatEngine.ENGINE_PROPERTY);
            advise = "若不想用 AgentScope，把这一行改成 legacy（或直接删掉）。";
        } else {
            head = """
                    配置 %s=%s 没有匹配到任何 ChatEngine 实现。
                    合法值只有 %s（默认，随 agent-kit-core 自带）与 %s（需额外引模块；匹配忽略大小写）。"""
                    .formatted(ChatEngine.ENGINE_PROPERTY, configured, ChatEngine.LEGACY, ChatEngine.AGENTSCOPE);
            advise = "若只是想用默认引擎，把这一行改成 legacy（或直接删掉）。";
        }
        return """
                %s

                用 AgentScope 需要同时做两件事，缺一不可：
                  1) 业务 pom 里加依赖：
                %s
                  2) 配置 agent-kit.engine=agentscope

                %s
                判据与完整说明见 docs/08-引擎选型.md。"""
                .formatted(head, indent(ADD_DEPENDENCY, "     "), advise);
    }

    /** 把依赖片段整体右移，免得贴进日志时和正文挤在一行看不出层级 */
    private static String indent(String block, String prefix) {
        return block.lines().map(line -> prefix + line).reduce((a, b) -> a + "\n" + b).orElse(block);
    }
}
