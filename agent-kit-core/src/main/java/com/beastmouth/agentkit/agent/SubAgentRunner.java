package com.beastmouth.agentkit.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.beastmouth.agentkit.agent.AgentResult.ToolEvent;
import com.beastmouth.agentkit.context.TurnHook;
import com.beastmouth.agentkit.tool.AgentTool;
import com.beastmouth.agentkit.tool.ToolInterceptor;
import com.beastmouth.agentkit.tool.ToolRegistry;
import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.model.ModelReply;
import com.beastmouth.agentkit.model.ToolSpec;
import com.beastmouth.agentkit.obs.ObsProperties;
import com.beastmouth.agentkit.obs.ObsHub;
import com.beastmouth.agentkit.obs.ObsWriter;
import com.beastmouth.agentkit.obs.ObservedModelCall;
import com.beastmouth.agentkit.obs.RunScope;
import com.beastmouth.agentkit.obs.ToolCallRecorder;
import com.beastmouth.agentkit.provider.ModelProvider;
import com.beastmouth.agentkit.provider.ModelProviderRegistry;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent 执行器：一个「决策 → 调工具 → 再决策」的循环。
 *
 * <p>协议不依赖厂商的 tool_calls 报文，而是让模型输出固定结构的 JSON：
 * {@code {"tools":[{"tool":"名字","args":{...}}]}} 或 {@code {"tool":null,"reply":"..."}}。
 * 好处是任意模型（包括不支持原生 function calling 的推理模型）都能用，换模型不用改代码。</p>
 *
 * <p>为什么一次可以传<b>多个</b>工具：用户的问句经常同时要两件事——「昨天花了多少钱，
 * 清单是什么」既要合计又要明细。若协议只允许一步一个工具，模型要么拆成两轮（多一次
 * 昂贵的模型调用），要么干脆只答一半。批量协议把「同一轮决策里的多个诉求」一次执行完，
 * 结果按顺序编号回灌，模型再做一次汇总即可。</p>
 *
 * <p>每一步的原始输出都会写回本地的消息数组，但**不会**进入主 Agent 的上下文——
 * 循环结束后只上交 {@link AgentResult}（结论 + 动作摘要）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubAgentRunner {

    /**
     * 工具调用协议（仅本引擎使用，新引擎走模型原生 function calling）。
     *
     * <p>已抽到 {@link PromptFragments}：回复风格是两套引擎的<b>共用口径</b>，
     * 各写一份必然分叉，用户会看到「切个引擎回答风格就变了」。
     */
    private static final String PROTOCOL = PromptFragments.PROTOCOL;

    /**
     * 回复风格。放在执行器而不是各子 Agent 的提示词里，是为了让所有领域共用一套
     * 输出规范：网页、IM、CLI 各端都按 Markdown 渲染，模型只要按结构写就各端都好看。
     */
    private static final String REPLY_STYLE = PromptFragments.REPLY_STYLE;

    /** 回执真实性规则，两套引擎共用（详见 {@link PromptFragments#TOOL_TRUTH}） */
    private static final String TOOL_TRUTH = PromptFragments.TOOL_TRUTH;

    private final ToolRegistry toolRegistry;
    private final ModelProviderRegistry providerRegistry;
    private final AiProperties properties;
    /** 可选：本轮钩子，没有实现时为空列表（Spring 对 List 注入空集合） */
    private final List<TurnHook> turnHooks;
    /** 可选：工具横切拦截器，没有实现时为空列表，等价于全部放行 */
    private final List<ToolInterceptor> toolInterceptors;
    private final ObservedModelCall observed;
    private final ObsWriter obsWriter;
    private final ObsProperties obsProperties;

    /**
     * 执行一个子 Agent。
     *
     * @param agent       子 Agent
     * @param history     该会话中属于本子 Agent 的历史消息（已隔离）
     * @param userMessage 用户本轮输入
     * @param sink        流式回调
     */
    public AgentResult run(Agent agent, List<ChatMessage> history, String userMessage, ChatSink sink) {
        return run(agent, history, userMessage, List.of(), sink);
    }

    /**
     * 执行一个子 Agent。
     *
     * @param agent       子 Agent
     * @param history     该会话中属于本子 Agent 的历史消息（已隔离）
     * @param userMessage 用户本轮输入
     * @param memories    与本轮输入相关的长期记忆行文本（可为空列表）
     * @param sink        流式回调
     */
    public AgentResult run(Agent agent, List<ChatMessage> history, String userMessage,
                           List<String> memories, ChatSink sink) {
        // 每轮钩子：业务侧预取数据、开作用域、埋自定义指标都在这里，脚手架只负责时机
        for (TurnHook hook : turnHooks) {
            try {
                hook.onTurnStart(userMessage);
            } catch (RuntimeException e) {
                log.warn("SubAgentRunner#run#轮次钩子失败 hook={} err={}",
                        hook.getClass().getSimpleName(), e.toString());
            }
        }
        // 一个用户请求 = 一个 run = 一条 trace；埋点失败只会退化为 NOOP，不影响回答
        RunScope scope = RunScope.begin(agent.name(), "WEB", userMessage, obsProperties.getPreviewChars());
        String status = "SUCCESS";
        String errorCode = null;
        try {
            AgentResult result = runInternal(agent, history, userMessage, memories, sink);
            // 回复原文交给 scope：P3 的"幻觉成功"规则要拿它和工具结果对账
            scope.setOutput(result.reply());
            return result;
        } catch (RuntimeException e) {
            status = "FAIL";
            errorCode = e.getClass().getSimpleName();
            throw e;
        } finally {
            obsWriter.finish(scope, status, errorCode);
            // 倒序收尾：后开的先关，和 try-with-resources 的语义一致
            for (int i = turnHooks.size() - 1; i >= 0; i--) {
                try {
                    turnHooks.get(i).onTurnEnd();
                } catch (RuntimeException e) {
                    log.warn("SubAgentRunner#run#轮次收尾失败 hook={} err={}",
                            turnHooks.get(i).getClass().getSimpleName(), e.toString());
                }
            }
            RunScope.clear();
        }
    }

    private AgentResult runInternal(Agent agent, List<ChatMessage> history, String userMessage,
                                    List<String> memories, ChatSink sink) {
        ModelProvider provider = providerRegistry.current();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(buildSystemPrompt(agent, memories)));
        messages.addAll(history);
        messages.add(ChatMessage.user(userMessage));

        List<ToolEvent> events = new ArrayList<>();
        for (int step = 1; step <= properties.getMaxSteps(); step++) {
            ModelReply reply = observed.chat(provider, messages, true, "ACT");
            JSONObject decision = parse(reply.content());
            if (decision == null) {
                RunScope.current().flagParseFail();
                log.warn("SubAgentRunner#run#协议解析失败 agent={} step={} raw={}", agent.name(), step,
                        abbreviate(reply.content()));
                // 工具已经成功执行过：结果本身就是答案，不能因为模型多说一句废话就把结果丢掉
                if (events.stream().anyMatch(ToolEvent::success)) {
                    return conclude(agent, provider, messages, events, null, sink);
                }
                // 推理型模型的思维链会吃掉 max-output-tokens，被截断时 content 为空，表现为「解析失败」
                String fallback = reply.content() == null || reply.content().isBlank()
                        ? "模型这次没有返回内容（可能输出被截断），请重试或调大 max-output-tokens。"
                        : "我没太理解这次的处理结果，能换个说法再试一次吗？";
                sink.delta(fallback);
                return new AgentResult(agent.name(), fallback, events);
            }

            List<ToolCall> calls = toolCalls(decision);
            if (calls.isEmpty()) {
                String answer = firstText(decision.getString("reply"), decision.getString("ask"));
                if ((answer == null || answer.isBlank()) && events.isEmpty() && step < properties.getMaxSteps()) {
                    // 空壳决策：既没有 tools 也没有 reply（实测 deepseek 偶发，输出一堆协议外的字段）。
                    // 还没执行过任何工具、也没到轮次上限，值得带着纠偏提醒再要一次；
                    // 已经有工具结果时不能重问——工具摘要本身就是答案，重问反而可能再调一次工具。
                    log.warn("SubAgentRunner#run#空壳决策（无 tools 无 reply）agent={} step={} raw={}",
                            agent.name(), step, abbreviate(reply.content()));
                    messages.add(ChatMessage.assistant(decision.toJSONString()));
                    messages.add(ChatMessage.user("你上一步的输出既没有 tools 也没有 reply。请严格按协议重新输出："
                            + "需要查数据或执行操作时输出 tools；已经能回答时输出 reply（写给用户看的完整回答，不能为空）。"));
                    continue;
                }
                return conclude(agent, provider, messages, events, answer, sink);
            }

            StringBuilder results = new StringBuilder();
            int index = 0;
            for (ToolCall call : calls) {
                index++;
                String prefix = calls.size() > 1 ? (index + ". ") : "";
                AgentTool tool = toolRegistry.find(call.name());
                if (tool == null) {
                    log.warn("SubAgentRunner#run#未知工具 agent={} tool={}", agent.name(), call.name());
                    // 模型幻觉出不存在的工具名，是「提示词/工具描述没写清」的直接证据，必须留痕
                    recordToolCall(call.name(), call.args(),
                            AgentTool.ToolOutcome.fail("工具不存在"), 0L, "NOT_FOUND",
                            ObsHub.tracing().startTool(RunScope.current().getSpan(), call.name(), argsJson(call.args())));
                    results.append(prefix).append("不存在名为 ").append(call.name())
                            .append(" 的工具，请从可用工具里选择。\n");
                    continue;
                }
                // 只推一句「正在干什么」，不要把整段工具说明推给前端：描述动辄上百字，
                // 在聊天页会被撑成一大段灰色文字，看起来像出错
                sink.status("tool", "正在" + shortLabel(tool));
                long toolStart = System.nanoTime();
                Span toolSpan = ObsHub.tracing().startTool(RunScope.current().getSpan(), call.name(), argsJson(call.args()));
                AgentTool.ToolOutcome outcome = execute(tool, call.args(), toolStart);
                recordToolCall(call.name(), call.args(), outcome,
                        (System.nanoTime() - toolStart) / 1_000_000, null, toolSpan);
                events.add(new ToolEvent(call.name(), outcome.success(), outcome.summary(), outcome.rows()));
                sink.tool(call.name(), outcome.success(), outcome.summary());
                log.info("SubAgentRunner#tool#agent={} tool={} success={} summary={}", agent.name(), call.name(),
                        outcome.success(), abbreviate(outcome.summary()));
                results.append(prefix)
                        .append(outcome.summary() == null || outcome.summary().isBlank() ? "无返回" : outcome.summary())
                        .append('\n');
            }
            messages.add(ChatMessage.assistant(decision.toJSONString()));
            messages.add(ChatMessage.user("工具结果（按调用顺序）：\n" + results.toString().strip()));
        }

        log.warn("SubAgentRunner#run#超过最大轮次 agent={} steps={}", agent.name(), properties.getMaxSteps());
        return conclude(agent, provider, messages, events, null, sink);
    }

    /**
     * 从决策里取出本轮要执行的工具，保持模型给的顺序。
     *
     * <p>兼容两种写法：{@code {"tools":[...]}} 与单个 {@code {"tool":...}}。
     * 模型偶尔会把两者混着写（既给 tools 又给 tool），以 tools 为准。</p>
     */
    private List<ToolCall> toolCalls(JSONObject decision) {
        List<ToolCall> calls = new ArrayList<>();
        JSONArray array = decision.getJSONArray("tools");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String name = text(item.getString("tool"));
                if (name != null) {
                    calls.add(new ToolCall(name, item.getJSONObject("args")));
                }
            }
        }
        if (calls.isEmpty()) {
            String single = text(decision.getString("tool"));
            if (single != null) {
                calls.add(new ToolCall(single, decision.getJSONObject("args")));
            }
        }
        return calls;
    }

    /** 收尾：有现成结论就直接用，否则让模型基于工具结果说一句话 */
    private AgentResult conclude(Agent agent, ModelProvider provider, List<ChatMessage> messages,
                                 List<ToolEvent> events, String directAnswer, ChatSink sink) {
        if (directAnswer != null && !directAnswer.isBlank()) {
            sink.delta(directAnswer);
            return new AgentResult(agent.name(), directAnswer, events);
        }
        // 只有一个成功动作时，工具摘要本身已经是人话，不必再花一次模型调用
        if (events.size() == 1 && events.get(0).success()) {
            sink.delta(events.get(0).summary());
            return new AgentResult(agent.name(), events.get(0).summary(), events);
        }
        if (events.isEmpty()) {
            String fallback = "暂时没处理成功，能再具体描述一下吗？";
            sink.delta(fallback);
            return new AgentResult(agent.name(), fallback, events);
        }
        messages.add(ChatMessage.user("请用自然的话向用户说明刚才的处理结果，多个结果按顺序说清楚，"
                + "需要列明细时用 Markdown 列表。不要输出 JSON，不要提工具名。"));
        StringBuilder buffer = new StringBuilder();
        try {
            observed.stream(provider, messages, chunk -> {
                buffer.append(chunk);
                sink.delta(chunk);
            }, "SUMMARIZE");
        } catch (RuntimeException e) {
            log.warn("SubAgentRunner#conclude#总结失败 agent={} err={}", agent.name(), e.toString());
            String joined = events.stream().map(ToolEvent::summary).reduce((a, b) -> a + "\n\n" + b).orElse("");
            if (buffer.isEmpty()) {
                sink.delta(joined);
            }
            return new AgentResult(agent.name(), joined, events);
        }
        String text = buffer.toString().trim();
        return new AgentResult(agent.name(), text.isEmpty() ? "处理完成" : text, events);
    }

    /**
     * 落一条工具明细。
     *
     * <p>实现全部收口在 {@link ToolCallRecorder}：AgentScope 引擎的工具走的是框架回调，
     * 那份代码与这里不共享对象、只共享口径——口径一旦分成两处写，空结果率与命中率
     * 就会出现「同一张报表、两段时间不可比」。</p>
     *
     * <p>埋点契约不变：<b>永不抛异常、永不阻塞业务线程</b>，只往当前 scope 的列表里加一项，
     * 真正的 insert 由 {@code ObsWriter} 的独立线程做。</p>
     */
    private void recordToolCall(String toolName, JSONObject args, AgentTool.ToolOutcome outcome,
                                long durationMs, String errorCode, Span span) {
        ToolCallRecorder.record(RunScope.current(), toolName, argsJson(args),
                outcome.success(), outcome.rows(), durationMs, errorCode, outcome.data(),
                span, obsProperties.getArgsChars());
    }

    /** 工具入参转 JSON（不截断，截断由 {@link ToolCallRecorder} 按列宽统一做） */
    private String argsJson(JSONObject args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        try {
            return args.toJSONString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 执行工具：先过拦截器链，再调工具本体，最后通知拦截器结果。
     *
     * <p>拦截器由业务提供（权限、审计、限流都在这里做），脚手架不认识任何策略。
     * 拦截返回的原因会原样回灌给模型，让模型如实转述而不是反复重试。</p>
     */
    private AgentTool.ToolOutcome execute(AgentTool tool, JSONObject args, long startNanos) {
        Map<String, Object> paramMap = args == null ? Map.of() : args;
        AgentTool.ToolOutcome outcome = null;
        try {
            for (ToolInterceptor interceptor : toolInterceptors) {
                String rejection;
                try {
                    rejection = interceptor.before(tool.name(), paramMap);
                } catch (RuntimeException e) {
                    log.warn("SubAgentRunner#execute#拦截器判定失败，按放行处理 interceptor={} tool={} err={}",
                            interceptor.getClass().getSimpleName(), tool.name(), e.toString());
                    continue;
                }
                if (rejection != null && !rejection.isBlank()) {
                    log.info("SubAgentRunner#execute#工具被拦截 tool={} interceptor={} reason={}",
                            tool.name(), interceptor.getClass().getSimpleName(), rejection);
                    outcome = AgentTool.ToolOutcome.fail(rejection + "。请如实告诉用户，不要重复尝试。");
                    return outcome;
                }
            }
            outcome = tool.execute(paramMap);
            return outcome;
        } catch (RuntimeException e) {
            log.warn("SubAgentRunner#execute#工具异常 tool={} err={}", tool.name(), e.toString());
            outcome = AgentTool.ToolOutcome.fail("执行失败：" + e.getMessage());
            return outcome;
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            AgentTool.ToolOutcome finalOutcome = outcome != null ? outcome
                    : AgentTool.ToolOutcome.fail("执行失败");
            for (ToolInterceptor interceptor : toolInterceptors) {
                try {
                    interceptor.after(tool.name(), paramMap, finalOutcome, durationMs);
                } catch (RuntimeException e) {
                    log.warn("SubAgentRunner#execute#拦截器收尾失败 interceptor={} tool={} err={}",
                            interceptor.getClass().getSimpleName(), tool.name(), e.toString());
                }
            }
        }
    }

    /**
     * 拼系统提示词。
     *
     * <h3>为什么顺序要当回事</h3>
     * <p>模型侧的前缀缓存（DeepSeek 的 {@code prompt_cache_hit_tokens}、Anthropic 的
     * {@code cache_control}）按<b>前缀完全匹配</b>计费，命中价通常只有未命中的 1/10~1/50。
     * 只要提示词里有一处「每轮都变」的内容出现在前面，它后面的稳定内容就全部作废。</p>
     *
     * <p>本方法有两套顺序，由 {@code agent-kit.prompt-order} 选择：</p>
     * <ul>
     *   <li>{@code v1}（旧）：角色 → 日期 → 思考开关 → 记忆 → 工具 → 协议 → 风格。
     *       记忆每轮都由 recall 重新生成，一变就把后面约 1–2k token 的工具与协议全部冲掉，
     *       实测第二轮命中率为 0；</li>
     *   <li>{@code v2}：角色 → 工具 → 协议 → 风格 → 日期/开关 → 记忆。
     *       稳定内容排在前面，易变内容压到最后，实测同一场景第二轮命中 73.5%。</li>
     * </ul>
     * <p>保留开关是为了能一键回退——改顺序会改变模型对「记忆的分量」的感受，属于会影响
     * 回答质量的变化，必须先用 eval 金标集验证再切默认值。</p>
     */
    private String buildSystemPrompt(Agent agent, List<String> memories) {
        List<ToolSpec> specs = toolRegistry.specs(agent.toolNames());
        return isStableOrder() ? stablePrefixPrompt(agent, memories, specs)
                : legacyPrompt(agent, memories, specs);
    }

    /** 顺序开关：只有显式配成 v2 才走新顺序，其余（含拼错）一律按旧顺序，避免配置笔误改变行为 */
    private boolean isStableOrder() {
        return "v2".equalsIgnoreCase(properties.getPromptOrder());
    }

    /**
     * v2：稳定 → 易变。
     *
     * <p>工具清单由 {@link ToolRegistry#specs} 保证按名字排序（{@code Set.of} 的迭代顺序
     * 每个进程都不同），这里是「提示词确定」的第二个前提。</p>
     */
    private String stablePrefixPrompt(Agent agent, List<String> memories, List<ToolSpec> specs) {
        StringBuilder sb = new StringBuilder(agent.systemPrompt());
        appendTools(sb, specs);
        sb.append(PROTOCOL);
        sb.append(TOOL_TRUTH);
        sb.append(REPLY_STYLE);
        // 分界线以下每天/每轮都会变，只能放在最后
        appendToday(sb);
        appendThinkFlag(sb);
        appendMemories(sb, memories);
        appendExtraInstructions(sb);
        return sb.toString();
    }

    /** v1：历史顺序，保留用于灰度回退与对照实验 */
    private String legacyPrompt(Agent agent, List<String> memories, List<ToolSpec> specs) {
        StringBuilder sb = new StringBuilder(agent.systemPrompt());
        appendToday(sb);
        appendThinkFlag(sb);
        appendMemories(sb, memories);
        appendTools(sb, specs);
        sb.append(PROTOCOL);
        sb.append(TOOL_TRUTH);
        sb.append(REPLY_STYLE);
        appendExtraInstructions(sb);
        return sb.toString();
    }

    /** 业务补充说明（术语、单位、行业规范）：脚手架不内置，由配置提供 */
    private void appendExtraInstructions(StringBuilder sb) {
        String extra = properties.getExtraInstructions();
        if (extra == null || extra.isBlank()) {
            return;
        }
        sb.append("\n\n").append(extra.trim()).append('\n');
    }

    private void appendTools(StringBuilder sb, List<ToolSpec> specs) {
        sb.append("\n\n可用工具：\n");
        specs.forEach(spec -> sb.append(spec.toPromptLine()).append('\n'));
    }

    private void appendToday(StringBuilder sb) {
        sb.append("\n\n今天是 ").append(LocalDate.now()).append(".");
    }

    private void appendThinkFlag(StringBuilder sb) {
        if (Boolean.FALSE.equals(properties.getThink())) {
            sb.append("\n直接给出最终结果，不要输出思考过程、推理步骤或额外解释。");
        }
    }

    private void appendMemories(StringBuilder sb, List<String> memories) {
        if (memories == null || memories.isEmpty()) {
            return;
        }
        sb.append("\n\n关于这位用户的长期记忆（帮他纠正口径、贴合习惯；与当前问题无关的不要硬套）：\n");
        memories.forEach(item -> sb.append("- ").append(item).append('\n'));
        sb.append("""
                记忆冲突处理：若某条「规则/记忆」与用户本轮说法不一致，以用户本轮说法为准执行，\
                并在回复里顺带说明「已按新口径更新」（不需要用户再确认）；\
                只有当用户说法本身含糊、无法确定新口径时，才先照最可能的口径做并追问一句。\
                """);
    }

    /** 容错解析：模型常带 ```json 包裹或前后加解释 */
    private JSONObject parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JSON.parseObject(text.substring(start, end + 1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) || "undefined".equalsIgnoreCase(trimmed)
                ? null : trimmed;
    }

    private String firstText(String... values) {
        for (String value : values) {
            String text = text(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private String abbreviate(String value) {
        // 日志用的版本保持「null 也返回空串」，避免调用方 .isBlank() 空指针
        String flat = abbreviate(value, 120);
        return flat == null ? "" : flat;
    }

    /**
     * 按指定长度截断。
     *
     * <p>落库时按列宽截断，不是按日志好看截断：不截断会撞 VARCHAR 上限，
     * 一条超长入参能让整批 insert 失败，连带丢掉同批的其他观测数据。</p>
     */
    private String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        String flat = value.replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    /**
     * 工具的一句话标签：取描述的第一个短句，超长再截断。
     *
     * <p>工具描述是写给模型看的（含参数说明、使用场景、反例），直接展示给用户就是噪声。
     * 取首个短句能保住「正在查询明细列表」这层意思，又不至于糊满一屏。</p>
     */
    private String shortLabel(AgentTool tool) {
        String description = tool.description();
        if (description == null || description.isBlank()) {
            return "处理";
        }
        String first = description.split("[，。；,;]", 2)[0].trim();
        return first.length() <= 18 ? first : first.substring(0, 18) + "…";
    }

    /** 一次待执行的工具调用（名字 + 原始参数） */
    private record ToolCall(String name, JSONObject args) {
    }
}
