package com.beastmouth.agentkit.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.beastmouth.agentkit.config.RouterProperties;
import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.model.ModelReply;
import com.beastmouth.agentkit.provider.ModelProvider;
import com.beastmouth.agentkit.provider.ModelProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 意图路由：判断一句话该交给哪个子 Agent。
 *
 * <p><b>领域知识全部来自注册表与配置，不写在这个类里</b>：候选清单由
 * {@link AgentRegistry#routingMenu()} 生成（每个 Agent 自己解释「什么请求归我」），
 * 能力边界与兜底关键词由 {@link RouterProperties} 提供。因此新增或替换子 Agent
 * 不需要动路由代码——这也是它能被两套引擎共用的前提。</p>
 *
 * <p><b>三层兜底，顺序不能换：</b></p>
 * <ol>
 *   <li>模型语义路由（主力，提示词写能力描述而不是关键词表，随子 Agent 数量线性扩展）；</li>
 *   <li>配置的关键词兜底（救「把刚才那条删掉」这种没有领域特征词、小模型容易判成闲聊的句子）；</li>
 *   <li>短句追问延续（救「那前天呢」这种语义全靠上文的省略句）。</li>
 * </ol>
 */
@Slf4j
@Component
public class IntentRouter {

    /**
     * 路由提示词（通用部分）。
     *
     * <p>只描述协议与判断原则；候选清单与业务边界分别由注册表和配置拼接在后面。
     * 「只输出 JSON」这类约束不能省——路由模型通常很小，一旦开始解释就会把 JSON 藏在
     * 自然语言里，解析失败等于每次都走兜底。</p>
     */
    private static final String ROUTING_PROMPT = """
            你是任务路由器，负责判断用户这句话该交给哪个子助手处理。
            只输出一个 JSON 对象，不要解释，不要 markdown 代码块：
            {"agents":["子助手名"]}
            绝大多数情况只需要一个；只有这句话确实横跨多个领域时才给多个，
            数组顺序就是执行顺序。单个也可以写成 {"agent":"子助手名"}。
            如果这句话与所有子助手的职责都无关（闲聊、问你是谁、超出能力范围），输出：
            {"agent":"none","reply":"一句简短、友好的回应"}
            宁可交给最接近的子助手让它如实说「做不到」，也不要自己编一个「已经支持」的回答。

            可选子助手：
            """;

    private final AgentRegistry agentRegistry;
    private final ModelProviderRegistry providerRegistry;
    private final RouterProperties routerProperties;

    /**
     * 观测收口，可为 {@code null}（单测直接 new 时不注入）。
     *
     * <p>路由这次调用发生在任何 run scope 之前，所以拿到的永远是 NOOP——
     * 走它只是为了把 token/成本记进 Prometheus，明细按设计不落库。</p>
     */
    private final com.beastmouth.agentkit.obs.ObservedModelCall observedModelCall;

    /** 容器装配用：带上观测收口，路由调用的成本才进得了指标 */
    @org.springframework.beans.factory.annotation.Autowired
    public IntentRouter(AgentRegistry agentRegistry, ModelProviderRegistry providerRegistry,
                        ObjectProvider<RouterProperties> routerProperties,
                        com.beastmouth.agentkit.obs.ObservedModelCall observedModelCall) {
        this.agentRegistry = agentRegistry;
        this.providerRegistry = providerRegistry;
        this.routerProperties = routerProperties.getIfAvailable(RouterProperties::new);
        this.observedModelCall = observedModelCall;
    }

    /**
     * 兼容单测的两参构造（不注入配置与观测，走默认值）。
     *
     * <p>不标 {@code @Autowired}：容器里有多个候选构造器时必须显式指定，
     * 否则启动期会报「expected single matching bean」这一类难读的错。</p>
     */
    public IntentRouter(AgentRegistry agentRegistry, ModelProviderRegistry providerRegistry) {
        this.agentRegistry = agentRegistry;
        this.providerRegistry = providerRegistry;
        this.routerProperties = new RouterProperties();
        this.observedModelCall = null;
    }

    /**
     * 路由一条用户消息。
     *
     * <p>命中子 Agent 时返回列表（可能多个，按顺序串行执行）；未命中时按
     * 「关键词兜底 → 短句追问延续 → 直接回话」接住，直答文本放在 {@link Route#directReply()}。</p>
     *
     * @param sink 流式回调，用于推送「正在理解」状态与直答文本；可传 {@link ChatSink#NOOP}
     */
    public Route route(List<ChatMessage> history, String userMessage, String lastAgentName, ChatSink sink) {
        sink.status("routing", "正在理解你的问题");
        ModelProvider provider = providerRegistry.current();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(buildRoutingPrompt()));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(userMessage));

        // 走观测收口而不是裸 provider：这一步的 token 也要进成本指标。
        // 注意此时没有 run scope（路由发生在委派之前），所以只会出指标、不落 ai_llm_call 明细。
        ModelReply reply = observedModelCall == null
                ? provider.chat(messages, true)
                : observedModelCall.chat(provider, messages, true, "ROUTE");
        JSONObject decision = parse(reply.content());
        List<Agent> agents = pick(decision);
        if (!agents.isEmpty()) {
            log.info("IntentRouter#route#命中 agents={}", agents.stream().map(Agent::name).toList());
            return new Route(agents, null);
        }
        // 路由模型通常很小，遇到「把刚才那条删掉」这种没有领域特征词的修正句容易判成闲聊。
        // 这里用配置的关键词兜底：宁可交给对应助手让它反问，也不要把明确的操作请求
        // 吞掉回一句「我可以帮你…」。
        Agent keywordAgent = keywordFallback(userMessage);
        if (keywordAgent != null) {
            log.info("IntentRouter#route#模型判为无关但命中兜底关键词，改交 agent={} raw={} msg={}",
                    keywordAgent.name(), abbreviate(reply.content()), userMessage);
            return new Route(List.of(keywordAgent), null);
        }
        // 关键词兜底接不住的省略句（「那前天呢」没有任何领域词），若上一轮是某个子助手在处理，
        // 就延续给它：子 Agent 手里有对话历史，能补全省略的部分；接不住也会如实说。
        Agent continuation = continuationFallback(userMessage, lastAgentName);
        if (continuation != null) {
            log.info("IntentRouter#route#短句追问延续上一助手={} raw={} msg={}",
                    continuation.name(), abbreviate(reply.content()), userMessage);
            return new Route(List.of(continuation), null);
        }
        String direct = trimToNull(decision == null ? null : decision.getString("reply"));
        String text = direct == null ? fallbackReply() : direct;
        sink.delta(text);
        log.info("IntentRouter#route#未委派 raw={} reply={}", abbreviate(reply.content()), text);
        return new Route(List.of(), text);
    }

    /** 路由提示词 = 通用协议 + 候选清单 + 业务补充说明（可选） */
    private String buildRoutingPrompt() {
        StringBuilder sb = new StringBuilder(ROUTING_PROMPT).append(agentRegistry.routingMenu());
        String instructions = routerProperties.getInstructions();
        if (instructions != null && !instructions.isBlank()) {
            sb.append('\n').append(instructions.trim()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 未委派时的直答话术。
     *
     * <p>配置了就用配置的；没配就从注册表生成，保证话术与实际能力一致——
     * 写死一句「我可以帮你下单」而部署里根本没有下单 Agent，是最容易穿帮的体验。</p>
     */
    private String fallbackReply() {
        String configured = routerProperties.getFallbackReply();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        List<Agent> agents = agentRegistry.all();
        if (agents.isEmpty()) {
            return "我暂时没有可以调用的能力。";
        }
        String abilities = agents.stream()
                .map(Agent::description)
                .filter(description -> description != null && !description.isBlank())
                .map(description -> description.split("[，。；,;]", 2)[0].trim())
                .distinct()
                .reduce((a, b) -> a + "、" + b)
                .orElse("处理你的请求");
        return "我目前可以帮你：" + abilities + "。";
    }

    /**
     * 关键词兜底。
     *
     * <p>只在模型判「无关」时才生效；命中后交给对应子 Agent，由它的工具判断
     * 「这件事到底该不该做、能不能做」，比在路由层猜更安全。</p>
     */
    private Agent keywordFallback(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }
        Map<String, List<String>> rules = routerProperties.getFallbackKeywords();
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            List<String> words = entry.getValue();
            if (words == null || words.isEmpty()) {
                continue;
            }
            if (words.stream().anyMatch(userMessage::contains)) {
                Agent agent = agentRegistry.find(entry.getKey());
                if (agent != null) {
                    return agent;
                }
                // 配了关键词却没注册对应 Agent：说明部署与配置不一致，留痕比静默跳过有用
                log.warn("IntentRouter#keywordFallback#配置指向未注册的 Agent，跳过 agent={}", entry.getKey());
            }
        }
        return null;
    }

    /**
     * 短句追问延续。
     *
     * <p>「那上周呢」「继续」这类省略句没有关键词，语义全靠上文，模型一旦失手就只能回无关话术。
     * 延续的判据刻意保守：短句 + 明确的省略信号。误伤的代价可控——延续错了，
     * 子 Agent 手里有历史、接不住会如实说。</p>
     */
    private Agent continuationFallback(String userMessage, String lastAgentName) {
        if (lastAgentName == null || lastAgentName.isBlank()) {
            return null;
        }
        String msg = userMessage == null ? "" : userMessage.trim();
        int maxLength = Math.max(1, routerProperties.getFollowUpMaxLength());
        if (msg.isEmpty() || msg.length() > maxLength) {
            return null;
        }
        List<String> signals = routerProperties.getFollowUpWords();
        boolean ellipsis = (signals != null && signals.stream().anyMatch(msg::contains))
                || msg.endsWith("呢")
                || "继续".equals(msg);
        if (!ellipsis) {
            return null;
        }
        return agentRegistry.find(lastAgentName);
    }

    private String abbreviate(String raw) {
        if (raw == null) {
            return "";
        }
        String flat = raw.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }

    /** 从路由决策里取出合法的子 Agent，保持模型给的顺序并去重 */
    private List<Agent> pick(JSONObject decision) {
        if (decision == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        JSONArray array = decision.getJSONArray("agents");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                String name = trimToNull(array.getString(i));
                if (name != null) {
                    names.add(name);
                }
            }
        }
        if (names.isEmpty()) {
            String single = trimToNull(decision.getString("agent"));
            if (single != null) {
                names.add(single);
            }
        }
        List<Agent> agents = new ArrayList<>();
        for (String name : new LinkedHashSet<>(names)) {
            Agent agent = agentRegistry.find(name);
            if (agent == null) {
                // "none" 是协议里的「不委派」标记，不是拼错的名字，不刷 warn
                if (!"none".equalsIgnoreCase(name)) {
                    log.warn("IntentRouter#pick#未知 agent={}", name);
                }
                continue;
            }
            agents.add(agent);
        }
        return agents;
    }

    private JSONObject parse(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JSON.parseObject(raw.substring(start, end + 1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    /** 路由结果：要么交给子 Agent（可能多个，按顺序执行），要么直接回复 */
    public record Route(List<Agent> agents, String directReply) {
    }
}
