package com.beastmouth.agentkit.provider;

import com.beastmouth.agentkit.agent.Agent;
import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.context.RunContext;
import com.beastmouth.agentkit.provider.ModelProviderRegistry.RoutePlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 决定「这一次模型调用走哪个渠道」。
 *
 * <p>解析顺序固定三层，先命中先赢：</p>
 * <ol>
 *   <li><b>请求标签</b>：{@code RunContext.labels["model.route"]}。同一个 Agent 服务多个业务线时，
 *       由业务入口按当前业务传值；不传就是「没意见」，不会误伤默认链路。</li>
 *   <li><b>Agent 声明</b>：{@link Agent#modelRoute()}。子 Agent 自己知道该用什么档位的模型
 *       （写操作要强模型、查询闲聊用便宜模型），写在代码里比写在配置里更难配错。</li>
 *   <li><b>默认渠道</b>：{@code agent-kit.provider} + {@code fallback-provider}，即改造前的行为。</li>
 * </ol>
 *
 * <h3>为什么请求标签排在最前</h3>
 * <p>标签是「本次业务上下文」，比「这个 Agent 通常用什么」更具体：同一条业务线可能被灰度到
 * 新模型，某个租户可能要私有化渠道。反过来若 Agent 声明优先，业务侧就失去了「临时切一下」
 * 的能力，只能改配置重启。</p>
 *
 * <h3>为什么不用方法参数传路由键</h3>
 * <p>{@code ChatEngine#handle} 一旦加参数，两套引擎、黄金集回放、IM 接入全要跟着改签名，
 * 而工具里那些深层调用点根本拿不到这个参数。{@link RunContext} 本来就是「一次运行」的上下文
 * 载体（同一条 ThreadLocal 链路，工具拦截器与观测都在读它），路由键放这里语义一致、零签名破坏。
 * 前提与其它上下文一样：<b>入口绑定 + finally 清理</b>，异步边界要显式传递。</p>
 */
@Slf4j
@Component
public class ModelRouter {

    /** 请求级路由键在 {@code RunContext.labels} 里的键名 */
    public static final String LABEL = "model.route";

    private final ModelProviderRegistry registry;
    private final AiProperties properties;

    public ModelRouter(ModelProviderRegistry registry, AiProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * 本次请求的路由键：请求标签 > Agent 声明 > {@code null}（默认渠道）。
     *
     * @param agent 当前要执行的子 Agent；意图路由、质量评判这类没有 Agent 的调用传 {@code null}
     */
    public String keyFor(Agent agent) {
        Object label = RunContext.label(LABEL);
        if (label != null && StringUtils.hasText(label.toString())) {
            return label.toString().trim();
        }
        if (agent != null && StringUtils.hasText(agent.modelRoute())) {
            return agent.modelRoute().trim();
        }
        return null;
    }

    /** 本次调用要用的渠道（已包好主备组合）。Agent 为 {@code null} 时只看请求标签 */
    public ModelProvider forAgent(Agent agent) {
        return registry.route(keyFor(agent));
    }

    /**
     * 本次调用的渠道组合。AgentScope 引擎用它自己造框架 {@code Model}，所以取纯数据而不是实例。
     */
    public RoutePlan planFor(Agent agent) {
        return registry.plan(keyFor(agent));
    }

    /**
     * 意图路由那一步的渠道。
     *
     * <p>配了 {@code agent-kit.intent-provider} 就用它（单渠道，不组合），否则跟随本次请求的路由。
     * 渠道 id 写错时不打死对话：记一条 error 再回落，毕竟这一步失败后面还有三层兜底。</p>
     */
    public ModelProvider forIntent() {
        String intent = properties.getIntentProvider();
        if (StringUtils.hasText(intent)) {
            try {
                return registry.byId(intent.trim());
            } catch (RuntimeException e) {
                log.error("ModelRouter#forIntent#intent-provider 不可用 id={} 可用={}，回落本次请求的路由",
                        intent, registry.availableIds());
            }
        }
        // 意图路由没有 Agent，只认请求标签：整条业务线走独立渠道时，
        // 路由那一步也该在同一个渠道里，否则日志和账单对不上
        return registry.route(keyFor(null));
    }
}
