package com.beastmouth.agentkit.agentscope.engine;

import com.alibaba.fastjson2.JSON;
import com.beastmouth.agentkit.agent.Agent;
import com.beastmouth.agentkit.agent.AgentRegistry;
import com.beastmouth.agentkit.agent.AgentResult;
import com.beastmouth.agentkit.agent.ChatEngine;
import com.beastmouth.agentkit.agent.ChatSink;
import com.beastmouth.agentkit.agent.StreamFailure;
import com.beastmouth.agentkit.agent.IntentRouter;
import com.beastmouth.agentkit.agent.PromptFragments;
import com.beastmouth.agentkit.agentscope.bridge.ToolBridge;
import com.beastmouth.agentkit.agentscope.config.AgentScopeProperties;
import com.beastmouth.agentkit.agentscope.model.ResilientModel;
import com.beastmouth.agentkit.provider.ModelProviderRegistry.RoutePlan;
import com.beastmouth.agentkit.provider.ModelRouter;
import com.beastmouth.agentkit.agentscope.obs.ObservingHook;
import com.beastmouth.agentkit.agentscope.obs.RuntimeObs;
import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.context.CompactOutcome;
import com.beastmouth.agentkit.context.CompactRequest;
import com.beastmouth.agentkit.context.CompactionProperties;
import com.beastmouth.agentkit.context.ContextCompactor;
import com.beastmouth.agentkit.context.RunContext;
import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.obs.LlmCallRecorder;
import com.beastmouth.agentkit.obs.RunScope;
import com.beastmouth.agentkit.support.AgentKitException;
import com.beastmouth.agentkit.support.ErrorCode;
import com.beastmouth.agentkit.tool.AgentTool;
import com.beastmouth.agentkit.tool.ToolInterceptor;
import com.beastmouth.agentkit.tool.ToolRegistry;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 基于 AgentScope 的对话引擎。
 *
 * <p>与自研引擎的关系是「二选一，不共存」：{@code agent-kit.engine=agentscope} 时由本类接管，
 * {@code legacy}（默认）时仍是 {@code MainAgent}。两者共用同一个 {@link ChatEngine} 契约，
 * 上层（控制器、IM 入口、黄金集）对此无感。</p>
 *
 * <p><b>复用了什么</b>：子 Agent 定义（{@link Agent}）、工具实现与拦截器（{@link ToolInterceptor}）、
 * 观测与质量规则全部原样使用。框架只接管「ReAct 循环」这一段——即
 * 「调模型 → 解析工具意图 → 执行工具 → 回灌结果 → 再调模型」。</p>
 *
 * <p><b>为什么不把子 Agent 拆成框架的多 Agent</b>：脚手架的默认编排是「一层浅路由 + 串行委派」，
 * 用框架的多 Agent 编排属于能力过剩；而且路由提示词与兜底关键词是长期调优的产物，
 * 换成框架自带的路由等于把调优结果清零。这里保守地保留原有路由决策，只用框架跑执行循环。</p>
 */
@Slf4j
public class AgentScopeEngine implements ChatEngine {

    /** 请求级上下文键：本轮召回的记忆，供 {@link PromptTailMiddleware} 压到提示词尾部 */
    static final String CTX_MEMORIES = "memories";

    /** 配置里的正则写错时的兜底：宁可少拦，也不能因为一个配置笔误让整轮对话起不来 */
    private static final String DEFAULT_WRITE_CLAIM_PATTERN =
            "已完成|已处理|已提交|已保存|已创建|已更新|已删除|操作成功";

    /**
     * 上下文超长的错误措辞。
     *
     * <p>各家写法不同（OpenAI 系 {@code context_length_exceeded}、Anthropic 系
     * {@code prompt is too long}、部分网关只回一句 {@code maximum context length}），
     * 这里只收<b>确定无歧义</b>的几种；宁可漏判（不重试），不可误判（每条失败都白打一次模型）。</p>
     */
    private static final List<String> OVERFLOW_HINTS = List.of(
            "context_length_exceeded", "maximum context", "context length",
            "token limit", "too many tokens", "reduce the length");

    private final AiProperties aiProperties;
    /**
     * 渠道路由（可空：单测/PoC 装配不传，此时退回全局默认渠道）。
     *
     * <p>与 legacy 引擎共用同一个 {@link ModelRouter}，解析顺序（请求标签 > Agent 声明 > 默认）
     * 只写一份——两套引擎各写一份的话，同一个配置在 legacy 生效、切到 AgentScope 失效，
     * 是最难查的一类问题。</p>
     */
    private final ModelRouter modelRouter;
    private final ToolRegistry toolRegistry;
    private final AgentRegistry agentRegistry;
    private final IntentRouter intentRouter;
    private final RuntimeObs obs;
    private final AgentScopeProperties scopeProperties;
    private final List<ToolInterceptor> toolInterceptors;
    private final Pattern writeClaimPattern;

    /**
     * 上下文压缩器，可为 null（单测 / 精简装配）。
     *
     * <p>与 legacy 引擎共用同一个实现：压缩策略若各写一份，同一个配置在两套引擎下
     * 表现不同，A/B 出来的差异就分不清是引擎造成的还是压缩造成的。</p>
     */
    private final ContextCompactor contextCompactor;

    /** 工具结果截断阈值等上下文治理配置 */
    private final CompactionProperties compactionProperties;

    /**
     * 子 Agent 的 Toolkit 缓存。
     *
     * <p>Toolkit 里是「每个工具解析好的 JSON Schema」，重建一次要重新解析全部 schema，
     * 而它本身只读——所以按子 Agent 缓存。桥接器本身无状态（请求级上下文全部走
     * {@link RuntimeContext}），共享是安全的。</p>
     */
    private final Map<String, Toolkit> toolkitCache = new ConcurrentHashMap<>();

    /**
     * 各子 Agent 的工具定义字符数（与 {@link #toolkitCache} 同生命周期）。
     *
     * <p>工具走原生 {@code tools} 字段，不占消息文本；而 legacy 把同样的内容写在 system 里。
     * 观测要两边可比，就得把这块单独补进 request_chars。</p>
     */
    private final Map<String, Integer> toolkitChars = new ConcurrentHashMap<>();

    /** 模型客户端缓存：内部持有 HTTP 连接池，重建会让每轮对话都新建一次连接 */
    private final Map<String, Model> modelCache = new ConcurrentHashMap<>();

    /**
     * 带熔断的模型缓存。
     *
     * <p><b>必须缓存</b>：熔断状态挂在 {@link ResilientModel} 实例上，每次请求新建一个
     * 等于冷却窗口永远是 0——过载期每个请求都要先撞一次首选。这里和 {@link #modelCache}
     * 一样按 provider 缓存，跨请求共享同一个熔断器。</p>
     */
    private final Map<String, Model> resilientCache = new ConcurrentHashMap<>();

    /**
     * 双模型（OpenAI 兼容 + Ollama）共用的 OkHttp 传输层，懒加载。
     *
     * <p><b>为什么不用框架默认的 JdkHttpTransport</b>（真实事故复盘）：
     * JDK HttpClient 的连接池取连接时<b>不查过期也不探活</b>（SSL 连接无法 peek），
     * 池内过期清理只在「连接归还」时顺带执行——上游长时间无流量后，闲置十几小时的
     * 死连接被盲复用，写请求成功但读到服务端的 FIN，15ms 即
     * {@code EOFException: EOF reached while reading}（新建连接至少 ~70ms 才走完 TLS）。
     * 换 OkHttp 后两个问题同时解决：池的 {@code keepAliveDuration}（默认 5 分钟）真正生效；
     * {@code retryOnConnectionFailure} 默认开启，复用到已死连接时会在收到响应字节前
     * 自动换一条连接重试，调用方无感。</p>
     */
    private volatile HttpTransport aiTransport;

    public AgentScopeEngine(AiProperties aiProperties, ToolRegistry toolRegistry,
                            AgentRegistry agentRegistry, IntentRouter intentRouter,
                            ModelRouter modelRouter,
                            RuntimeObs obs, AgentScopeProperties scopeProperties,
                            List<ToolInterceptor> toolInterceptors,
                            ContextCompactor contextCompactor,
                            CompactionProperties compactionProperties) {
        this.aiProperties = aiProperties;
        this.modelRouter = modelRouter;
        this.toolRegistry = toolRegistry;
        this.agentRegistry = agentRegistry;
        this.intentRouter = intentRouter;
        this.obs = obs == null ? RuntimeObs.DISABLED : obs;
        this.scopeProperties = scopeProperties == null ? new AgentScopeProperties() : scopeProperties;
        this.toolInterceptors = toolInterceptors == null ? List.of() : List.copyOf(toolInterceptors);
        this.contextCompactor = contextCompactor;
        this.compactionProperties = compactionProperties == null
                ? new CompactionProperties() : compactionProperties;
        this.writeClaimPattern = compileClaimPattern(this.scopeProperties.getWriteClaimPattern());
    }

    /** 无观测装配时使用（单测、PoC）：不建 run、不落库、不打点 */
    public AgentScopeEngine(AiProperties aiProperties, ToolRegistry toolRegistry,
                            AgentRegistry agentRegistry, IntentRouter intentRouter) {
        this(aiProperties, toolRegistry, agentRegistry, intentRouter, null,
                RuntimeObs.DISABLED, new AgentScopeProperties(), List.of(), null, null);
    }

    private static Pattern compileClaimPattern(String regex) {
        if (!StringUtils.hasText(regex)) {
            return Pattern.compile(DEFAULT_WRITE_CLAIM_PATTERN);
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            log.error("AgentScopeEngine#init#write-claim-pattern 不是合法正则，改用默认值 pattern={} err={}",
                    regex, e.toString());
            return Pattern.compile(DEFAULT_WRITE_CLAIM_PATTERN);
        }
    }

    @Override
    public String engineName() {
        return ChatEngine.AGENTSCOPE;
    }

    @Override
    public AgentResult handle(List<ChatMessage> history, String userMessage, String lastAgentName,
                              List<String> memories, ChatSink sink) {
        if (agentRegistry.isEmpty()) {
            throw AgentKitException.of(ErrorCode.AGENT_DISABLED);
        }
        // 路由与 legacy 引擎共用同一个 IntentRouter：提示词和三层兜底是长期调优的结果，
        // 复制一份到新引擎里，A/B 出来的差异就分不清是引擎造成的还是路由造成的
        IntentRouter.Route route = intentRouter.route(history, userMessage, lastAgentName, sink);
        if (route.agents().isEmpty()) {
            return new AgentResult("main", route.directReply(), List.of());
        }

        List<AgentResult> results = new ArrayList<>();
        List<Agent> agents = route.agents();
        for (int i = 0; i < agents.size(); i++) {
            if (i > 0) {
                // 串行执行时前一段正文已流给前端，补空行避免两段回答粘在一起
                sink.delta("\n\n");
            }
            sink.status("delegate", agents.size() > 1
                    ? "正在处理（%d/%d）".formatted(i + 1, agents.size())
                    : "正在处理");
            try {
                results.add(runOne(agents.get(i), history, userMessage, memories, sink));
            } catch (RuntimeException e) {
                // 与 legacy 引擎同一条规矩：前面几段正文已经发出去了，就不能再让整条连接失败。
                // 一个字都没输出才上抛（由 Controller 转成错误响应体）
                AgentResult done = merge(results);
                if (done.reply() == null || done.reply().isBlank()) {
                    throw e;
                }
                StreamFailure.report(sink, e);
                log.warn("AgentScopeEngine#handle#第 {} 个子 Agent 失败，保留前 {} 段正文收尾 err={}",
                        i + 1, results.size(), e.toString());
                return done;
            }
        }
        return merge(results);
    }

    /**
     * 跑一个子 Agent：复用其系统提示词与工具白名单，走框架的 ReAct 循环。
     *
     * <p><b>run 生命周期在这里</b>，与 legacy 的 {@code SubAgentRunner.run} 一一对应：
     * 一个子 Agent 一轮 = 一条 {@code ai_run} = 一条 trace。不在这里开的话，
     * 新引擎的成本、成功率、空结果率在报表上完全没有数据，而且不会报错。</p>
     */
    private AgentResult runOne(Agent target, List<ChatMessage> history, String userMessage,
                               List<String> memories, ChatSink sink) {
        RunScope scope = obs.begin(target.name(), userMessage);
        ChatSink out = obs.ttftAware(sink, scope);
        String status = "SUCCESS";
        String errorCode = null;
        // 这两个必须声明在 try 外面：catch 要靠 streamed 判断「有没有已经把正文吐出去」，
        // 已经吐过就只能推错误帧收尾，不能再抛（响应头早发出去了）。
        // 声明在 try 里的话 catch 拿不到，就只能一律上抛——正是要修的那个问题。
        List<AgentResult.ToolEvent> events = new CopyOnWriteArrayList<>();
        StringBuilder streamed = new StringBuilder();
        try {
            ReActAgent reactAgent = buildAgent(target, scope);

            RuntimeContext ctx = buildRuntimeContext(memories, scope);
            // 压缩必须发生在 toMessages 之前：它处理的是「我们自己的历史模型」，
            // 而不是框架的 Msg。放在这条线上还有一个好处——legacy 引擎能用同一份实现。
            List<ChatMessage> effective = compactHistory(history, userMessage, false, target.name());
            List<Msg> messages = toMessages(effective, userMessage);

            Event last;
            try {
                last = reactAgent.stream(messages, StreamOptions.builder().build(), ctx)
                        .doOnNext(event -> dispatch(event, out, events, streamed))
                        .blockLast();
            } catch (RuntimeException e) {
                // 只要正文还没流出去，就可以用更激进的压缩再试一次；已经流出去的不能重来，
                // 否则前端会看到两段重复的回答
                List<ChatMessage> forced = streamed.isEmpty()
                        ? compactHistory(history, userMessage, true, target.name())
                        : history;
                if (forced == history || !isContextOverflow(e)) {
                    log.error("AgentScopeEngine#runOne#执行失败 agent={} err={}", target.name(), e.toString());
                    throw e;
                }
                log.warn("AgentScopeEngine#runOne#上下文溢出，强制压缩后重试一次 agent={} 原条数={} 压缩后={} err={}",
                        target.name(), history.size(), forced.size(), e.toString());
                messages = toMessages(forced, userMessage);
                try {
                    last = reactAgent.stream(messages, StreamOptions.builder().build(), ctx)
                            .doOnNext(event -> dispatch(event, out, events, streamed))
                            .blockLast();
                } catch (RuntimeException retryError) {
                    log.error("AgentScopeEngine#runOne#压缩重试仍失败 agent={} err={}",
                            target.name(), retryError.toString());
                    throw retryError;
                }
            }

            String reply = last == null || last.getMessage() == null ? "" : textOf(last.getMessage());
            if (!StringUtils.hasText(reply)) {
                // 兜底用流式增量拼出来的正文：某些路径下最后一个事件的 Msg 可能只有工具结果
                reply = streamed.toString();
            }
            if (!StringUtils.hasText(reply)) {
                // 走到这里说明整轮一句正文都没有：模型空了、或最后一轮只剩工具结果、或被 maxIters 截断。
                // 不能把空字符串交给上层——前端会渲染出一片空白，用户以为消息丢了；而 ai_run 记的是
                // 一条成功的 run，报表也看不出异常。legacy 引擎一直有这层兜底，新引擎不能没有。
                reply = fallbackReply(events);
                out.delta(reply);
                log.warn("AgentScopeEngine#runOne#整轮无正文，使用兜底答复 agent={} 工具数={} 摘要={}",
                        target.name(), events.size(), reply);
            } else if (streamed.isEmpty()) {
                // 整轮没有任何增量（模型不走流式、或全被过滤），至少把完整答复推一次，
                // 否则用户会盯着「正在处理」等到结束却什么都没看到
                out.delta(reply);
            }
            // 空口回执守门：回复声称写操作成功但本轮零工具调用 → 自动补一轮强制纠正
            reply = guardGhostReceipt(reactAgent, messages, ctx, reply, events, out, target.name());
            // 回复原文交给 scope：质量规则要拿它和工具结果对账（幻觉成功检测）
            scope.setOutput(reply);
            log.info("AgentScopeEngine#runOne#完成 agent={} 动作数={} 回复长度={}",
                    target.name(), events.size(), reply.length());
            return new AgentResult(target.name(), reply, List.copyOf(events));
        } catch (RuntimeException e) {
            // 部分成功也是失败：状态记 FAIL、错误码照记，报表口径不变。
            // 区别只在返回路径——已经流出去的正文不能丢，也不能让连接悬着
            status = "FAIL";
            errorCode = e.getClass().getSimpleName();
            if (!streamed.isEmpty()) {
                // 一个字都已经发出去了：推一帧 error + 正常结束，保留已产出的正文。
                // 用 out 而不是 sink：真项目里 out 可能还带着首字打点等装饰，
                // 错误帧跳过装饰层会让「最后一帧」和「前面的帧」走不同的通道
                return StreamFailure.partial(target.name(), streamed.toString(), events, out, e);
            }
            throw normalizeFailure(e);
        } finally {
            obs.finish(scope, status, errorCode);
        }
    }

    /**
     * 压缩历史。
     *
     * <p>返回原列表<b>引用</b>表示「没有发生压缩」——调用方靠引用比较判断能不能用更激进的
     * 参数重试。这不是取巧：未压缩时本来就不该复制一遍列表（热路径每轮都走这里）。</p>
     */
    private List<ChatMessage> compactHistory(List<ChatMessage> history, String userMessage,
                                             boolean force, String agentName) {
        if (contextCompactor == null || !contextCompactor.enabled()) {
            return history;
        }
        CompactRequest request = CompactRequest.of(RunContext.subjectId(), history, userMessage);
        CompactOutcome outcome = contextCompactor.compact(force ? request.forced() : request);
        if (outcome == null || !outcome.compacted()) {
            return history;
        }
        log.info("AgentScopeEngine#compact#上下文已压缩 agent={} 原条数={} 压缩条数={} 保留={} reused={} 省字符={}",
                agentName, history.size(), outcome.coveredCount(),
                outcome.history().size() - 1, outcome.reused(), outcome.savedChars());
        return outcome.history();
    }

    /**
     * 是否「上下文超长」类错误。
     *
     * <p>只认这几种措辞：误判会让每个普通失败都白打一次模型，成本比不重试更高。
     * 判断沿异常链走——框架/传输层常把真正的错误包一层再抛。</p>
     */
    private boolean isContextOverflow(Throwable error) {
        if (contextCompactor == null || !contextCompactor.retryOnOverflow()) {
            return false;
        }
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase();
            for (String hint : OVERFLOW_HINTS) {
                if (lower.contains(hint)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 把框架/传输层的异常归一化成 {@code MODEL_UNAVAILABLE}。
     *
     * <p>legacy 引擎的 provider 自己抛 {@link AgentKitException}，消息是中文、带错误码；
     * AgentScope 的异常来自框架与 OkHttp，直接上抛会让用户看到
     * {@code WebClientRequestException: Connection refused} 这种东西——
     * 既看不懂，也不该出现在前端。所以在这里做一次翻译，原始异常留在 cause 里供排查。</p>
     *
     * <p>只翻译「确定是传输/超时」的那类；其余原样上抛，免得把空指针包装成「模型挂了」，
     * 让人往错的方向查。</p>
     */
    private static RuntimeException normalizeFailure(RuntimeException e) {
        if (e instanceof AgentKitException || !StreamFailure.isTransportFailure(e)) {
            return e;
        }
        return AgentKitException.of(ErrorCode.MODEL_UNAVAILABLE, ErrorCode.MODEL_UNAVAILABLE.message(), e);
    }

    /**
     * 整轮没有正文时的兜底答复。
     *
     * <p>规则与 legacy 的 {@code SubAgentRunner#conclude} 一致：有成功执行过的工具，
     * 就把它的摘要当答案（摘要本来就是给人看的一句话）；一个工具都没成功，才说
     * 「没处理成功」。<b>不能用「处理完成」这类话糊过去</b>——用户会以为事情办成了，
     * 而实际上什么都没发生。</p>
     */
    static String fallbackReply(List<AgentResult.ToolEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentResult.ToolEvent event = events.get(i);
            if (event.success() && StringUtils.hasText(event.summary())) {
                return event.summary();
            }
        }
        return "暂时没处理成功，能再具体描述一下吗？";
    }

    /** 空口回执的强制纠正指令，作为 user 消息追加在原对话之后 */
    static final String GHOST_RECEIPT_NUDGE = """
            （系统复核）你上一条回复声称操作已完成，但本轮你没有执行任何工具调用，
            系统已判定这是无效回执。请立即真实调用工具完成该操作，然后按工具的真实
            返回结果回复；若信息不足以调用工具，就明确告诉用户刚才没有实际执行、
            并说明缺什么，严禁再次声称已完成。
            """;

    /** 回复里是否出现了「完成态回执」的字样；判定词由配置提供，不同业务口径不同 */
    boolean claimsWriteSuccess(String reply) {
        return reply != null && writeClaimPattern.matcher(reply).find();
    }

    /**
     * 空口回执守门：回复声称操作成功，但本轮一个工具都没调，自动追加一轮强制纠正。
     *
     * <p><b>事故背景</b>（真实案例）：用户发「昨天买咖啡花了88」，模型零工具调用、
     * 16 个 token 直接回「记好了」，账本里什么都没有。根因是会话历史里只有最终文本、
     * 不带工具痕迹，模型从上下文里学会了「报账 = 直接答记好了」。这个失败模式与业务无关，
     * 凡是「写操作 + 模型会模仿历史」的 Agent 都会遇到。</p>
     *
     * <p><b>为什么在引擎层拦而不是只靠 prompt</b>：prompt 是事前劝告，对「模仿自己
     * 历史」这种失败模式不可靠；这里在 run 生命周期内确定性拦截——回执是假的就重问，
     * 不依赖模型自觉。纠正轮产生的工具事件追加进 {@code events}，观测表里
     * {@code tool_call_count} 会如实反映。</p>
     *
     * <p>触发条件刻意保守：只在 {@code events.isEmpty()} 且回复命中判定词时触发，
     * 正常轮次（有工具）零开销。</p>
     */
    private String guardGhostReceipt(ReActAgent agent, List<Msg> messages, RuntimeContext ctx,
                                     String reply, List<AgentResult.ToolEvent> events,
                                     ChatSink out, String agentName) {
        if (!scopeProperties.isWriteClaimGuard() || !events.isEmpty() || !claimsWriteSuccess(reply)) {
            return reply;
        }
        log.warn("AgentScopeEngine#runOne#空口回执拦截 agent={} 零工具但声称成功 reply={}",
                agentName, reply);
        out.status("verify", "复核回执");

        List<Msg> retry = new ArrayList<>(messages);
        retry.add(new UserMessage(GHOST_RECEIPT_NUDGE));
        StringBuilder reStreamed = new StringBuilder();
        Event reLast;
        try {
            reLast = agent.stream(retry, StreamOptions.builder().build(), ctx)
                    .doOnNext(event -> dispatch(event, out, events, reStreamed))
                    .blockLast();
        } catch (RuntimeException e) {
            // 复核轮挂了也不能让「已完成」裸奔给用户：补一句明示，宁可难看不可误导
            log.warn("AgentScopeEngine#runOne#复核轮失败 agent={} err={}", agentName, e.toString());
            String disclaimer = "（系统提示：刚才那句回执未经工具执行，可能没有真正生效。）";
            out.delta(disclaimer);
            return reply + disclaimer;
        }
        String newReply = reLast == null || reLast.getMessage() == null ? "" : textOf(reLast.getMessage());
        if (!StringUtils.hasText(newReply)) {
            newReply = reStreamed.toString();
        }
        if (!StringUtils.hasText(newReply)) {
            return reply;
        }
        if (events.isEmpty() && claimsWriteSuccess(newReply)) {
            // 纠正轮后依旧零工具 + 依旧声称成功：模型救不回来了，明示用户别信那句回执
            String disclaimer = "（系统提示：刚才的回执未经工具执行确认，可能没有真正生效，请核实。）";
            out.delta(disclaimer);
            return newReply + disclaimer;
        }
        return newReply;
    }

    /** 多个子 Agent 的结果拼成一条回复，行为与 legacy 引擎的 merge 保持一致 */
    private AgentResult merge(List<AgentResult> results) {
        if (results.size() == 1) {
            return results.get(0);
        }
        StringBuilder reply = new StringBuilder();
        Set<String> names = new LinkedHashSet<>();
        List<AgentResult.ToolEvent> events = new ArrayList<>();
        for (AgentResult result : results) {
            names.add(result.agentName());
            events.addAll(result.events());
            if (result.reply() == null || result.reply().isBlank()) {
                continue;
            }
            if (!reply.isEmpty()) {
                reply.append("\n\n");
            }
            reply.append(result.reply().strip());
        }
        // 任一段是半截的，整体就是半截：合并后仍要能被上层识别出来
        boolean partial = results.stream().anyMatch(AgentResult::partial);
        return new AgentResult(String.join("+", names), reply.toString(), events, partial);
    }

    /**
     * 构造请求级上下文。
     *
     * <p>调用方标识在这里显式带进 Reactor 链路——这是整条链路最关键的一处：
     * 业务与工具都靠 {@link RunContext}（TransmittableThreadLocal）取调用方，
     * 而工具在 {@code boundedElastic} 线程执行时读不到。不显式透传的后果不是报错，
     * 是「查询少了隔离条件」的静默越权。</p>
     */
    private RuntimeContext buildRuntimeContext(List<String> memories, RunScope scope) {
        RunContext.Subject subject = RunContext.current();

        RuntimeContext.Builder builder = RuntimeContext.builder();
        if (subject != null && subject.id() != null) {
            builder.userId(subject.id());
        }
        builder.put(ToolBridge.CTX_SUBJECT, RunContext.Subject.class, subject);
        // 观测上下文同样显式透传：工具在 Reactor 线程执行，TTL 拿不到 RunScope，
        // 结果就是 ai_tool_call 全程为空而 ai_run 却有工具调用次数——两边对不上才发现
        builder.put(ToolBridge.CTX_RUN_SCOPE, RunScope.class, scope);
        // 语言也一样要带过去：按语言分片的缓存 key 读 LocaleContextHolder。不带的话
        // 工具线程回落到 JVM 默认语言，不报错，只是悄悄串味
        builder.put(ToolBridge.CTX_LANG, String.class,
                LocaleContextHolder.getLocale().toLanguageTag());
        if (memories != null && !memories.isEmpty()) {
            builder.put(CTX_MEMORIES, List.class, memories);
        }
        return builder.build();
    }

    /**
     * 历史消息转框架消息。
     *
     * <p>{@code system} 角色的历史一律丢弃：它已由 {@code sysPrompt} 承担，
     * 再以 user/assistant 之外的第三条 system 消息塞进去，会破坏前缀缓存的稳定性
     * （每轮位置不同）。</p>
     */
    private List<Msg> toMessages(List<ChatMessage> history, String userMessage) {
        List<Msg> messages = new ArrayList<>();
        if (history != null) {
            for (ChatMessage message : history) {
                if (message == null || !StringUtils.hasText(message.content())) {
                    continue;
                }
                if ("assistant".equals(message.role())) {
                    messages.add(new AssistantMessage(message.content()));
                } else if ("user".equals(message.role())) {
                    messages.add(new UserMessage(message.content()));
                }
            }
        }
        messages.add(new UserMessage(userMessage));
        return messages;
    }

    /**
     * 把框架事件翻译成脚手架的三类流式回调。
     *
     * <p><b>实测的事件形态</b>（一轮工具型对话约 750 个事件）：</p>
     * <ul>
     *   <li>正文是逐字到达的：{@code REASONING} 事件里一堆 {@code last=false} 的
     *       {@link TextBlock}，每块 1~3 个字符，最后再给一条 {@code last=true} 的完整汇总；</li>
     *   <li>工具参数也是流式的：{@code ToolUseBlock} 先给真实工具名，随后是一串
     *       {@code __fragment__} 占位块，直到 {@code last=true} 才给出完整的调用；</li>
     *   <li>{@link ThinkingBlock} 是模型的思考过程，数量最多，对用户无意义。</li>
     * </ul>
     *
     * <p>因此：正文推增量但跳过汇总块（否则同一段话推两遍）；工具状态只在
     * {@code last=true} 时推一次（否则一个工具调用会刷几十条状态帧）。</p>
     */
    private void dispatch(Event event, ChatSink sink, List<AgentResult.ToolEvent> events,
                          StringBuilder streamed) {
        if (event == null || event.getMessage() == null) {
            return;
        }
        List<ContentBlock> blocks = event.getMessage().getContent();
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text) {
                dispatchText(event, text, sink, streamed);
            } else if (block instanceof ThinkingBlock) {
                // 思考块整体忽略：对用户只暴露「理解中 / 执行中」两个状态
                continue;
            } else if (block instanceof ToolUseBlock use) {
                dispatchToolUse(event, use, sink);
            } else if (block instanceof ToolResultBlock result) {
                boolean success = result.getState() != null
                        && "success".equalsIgnoreCase(result.getState().getValue());
                String summary = textOfBlock(result);
                Integer rows = extractRows(result);
                sink.tool(result.getName(), success, summary);
                events.add(new AgentResult.ToolEvent(result.getName(), success, summary, rows));
            }
        }
    }

    /**
     * 正文增量。
     *
     * <p>只推 {@code last=false} 的块。{@code last=true} 的那条是这一轮的完整汇总，
     * 前面的增量已经把同样的字推给用户了，再推一遍前端会看到重复段落。</p>
     *
     * <p>{@code AGENT_RESULT} 也跳过：它同样是完整文本，属于「给调用方落库」的用途，
     * 不是增量。若整轮都没出现过增量（某些模型不支持流式），由 {@link #runOne}
     * 在收尾时补推一次，保证用户至少能看到回答。</p>
     */
    private void dispatchText(Event event, TextBlock text, ChatSink sink, StringBuilder streamed) {
        if (event.isLast() || event.getType() == EventType.AGENT_RESULT) {
            return;
        }
        String content = text.getText();
        // 注意用 isEmpty 而不是 hasText：流式增量里「单独一个空格」也是有效内容，
        // hasText 会把它当成空白丢掉，结果是落库的答复带空格、前端显示的不带
        if (content == null || content.isEmpty()) {
            return;
        }
        sink.delta(content);
        streamed.append(content);
    }

    /**
     * 工具调用状态。
     *
     * <p>必须过滤 {@code __fragment__}——那是框架把工具参数按流式分片回传时的占位名，
     * 不是真实工具。不过滤的话，一次调用会往前端推几十条「正在处理」，
     * 而真实工具名只出现一次。</p>
     */
    private void dispatchToolUse(Event event, ToolUseBlock use, ChatSink sink) {
        String name = use.getName();
        if (name == null || name.isBlank() || name.startsWith("__")) {
            return;
        }
        // 只在完整调用到达时推一次状态；中间的分片不推
        if (!event.isLast()) {
            return;
        }
        sink.status("tool", "正在" + readableName(name));
    }

    /** 从工具结果的 metadata 里取回条数，供幻觉检测与质量规则使用 */
    private Integer extractRows(ToolResultBlock result) {
        Map<String, Object> metadata = result.getMetadata();
        if (metadata == null) {
            return null;
        }
        Object rows = metadata.get("rows");
        if (rows instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private String textOfBlock(ToolResultBlock result) {
        List<ContentBlock> output = result.getOutput();
        if (output == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : output) {
            if (block instanceof TextBlock text && text.getText() != null) {
                sb.append(text.getText());
            }
        }
        return sb.toString();
    }

    private String textOf(Msg msg) {
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text && text.getText() != null) {
                sb.append(text.getText());
            }
        }
        return sb.toString();
    }

    /**
     * 工具名转人话，用于前端状态行。
     *
     * <p>映射由 {@code agent-kit.agentscope.tool-labels} 提供：脚手架不认识
     * {@code order_create} 该显示成「创建订单」还是「下单」，这属于业务口径。</p>
     */
    private String readableName(String toolName) {
        if (toolName == null) {
            return scopeProperties.getDefaultToolLabel();
        }
        String label = scopeProperties.getToolLabels().get(toolName);
        if (StringUtils.hasText(label)) {
            return label;
        }
        String fallback = scopeProperties.getDefaultToolLabel();
        return StringUtils.hasText(fallback) ? fallback : toolName;
    }

    /**
     * 构建一个子 Agent 实例（<b>每次请求一个</b>）。
     *
     * <h3>为什么不再跨请求复用 Agent</h3>
     * <p>观测 Hook 必须持有本请求的 {@link RunScope}（Reactor 线程上 TTL 是空的，
     * 见 {@link ObservingHook} 的说明），而 Hook 注册在 Agent 上——两者只能同生共死。
     * 复用 Agent 的代价是观测全部失效；复用的收益只是省一次对象构造
     * （schema 解析在 {@link #toolkitCache} 里，模型客户端在 {@link #modelCache} 里，都没丢）。
     * 顺带修掉一个隐患：Agent 的会话状态是按 (userId, sessionId) 缓存的，而我们没设
     * {@code defaultSessionId}，复用同一个实例时有把上一轮历史带进下一轮的风险。</p>
     */
    private ReActAgent buildAgent(Agent target, RunScope scope) {
        Toolkit toolkit = toolkitCache.computeIfAbsent(target.name(), name -> buildToolkit(target));

        // 渠道按路由解析，与 legacy 共用同一份规则：
        // 请求标签（RunContext.labels[model.route]）> Agent 自己声明 > 全局默认
        RoutePlan plan = planFor(target);

        ReActAgent.Builder builder = ReActAgent.builder()
                .name(target.name())
                .description(target.description())
                // 回复风格必须带上，且必须放在 sysPrompt（稳定区）而不是中间件尾部：
                // 它是产品口径，与 legacy 共用同一份常量；放在易变区（日期/记忆之后）
                // 会让它成为「每轮都要求模型重新读一遍」的尾部内容，成本更高还没必要
                .sysPrompt(target.systemPrompt() + PromptFragments.TOOL_TRUTH + PromptFragments.REPLY_STYLE)
                .model(resilientModel(plan))
                .toolkit(toolkit)
                .maxIters(aiProperties.getMaxSteps())
                .middleware(new PromptTailMiddleware(Boolean.FALSE.equals(aiProperties.getThink())));

        LlmCallRecorder recorder = obs.recorder();
        if (recorder != null && scope != null && !scope.noop()) {
            // 记实际渠道而不是全局默认：按业务分流之后，trace 与指标必须能按渠道分组，
            // 否则所有调用都被标成同一个 provider，「这句话是哪个模型答的」永远查不出来
            builder.hook(new ObservingHook(scope, plan.primary(), recorder,
                    toolkitChars.getOrDefault(target.name(), 0)));
        }

        return builder.build();
    }

    /**
     * 带熔断的主模型。
     *
     * <p>不用框架的 {@code fallbackModel}：它只在「这次失败」时换备用，不记冷却，
     * 过载窗口里每个请求都要先撞一次首选白等十几秒。主备与冷却的语义由
     * {@link ResilientModel} 承担，与 legacy 的 {@code FailoverModelProvider} 对齐。</p>
     *
     * <p>只有首选才包备用：备用自己再包一层首选会绕成环，而且备用没有「备用的备用」。</p>
     */
    /**
     * 本条路由对应的模型组合解析结果。
     *
     * <p>没有注入路由器（单测 / PoC）时退回全局默认渠道，行为与改造前一致。</p>
     */
    private RoutePlan planFor(Agent target) {
        if (modelRouter != null) {
            return modelRouter.planFor(target);
        }
        return new RoutePlan(null, aiProperties.getProvider(), aiProperties.getFallbackProvider());
    }

    /**
     * 带熔断的主模型，按「渠道组合」缓存。
     *
     * <p>不用框架的 {@code fallbackModel}：它只在「这次失败」时换备用，不记冷却，
     * 过载窗口里每个请求都要先撞一次首选白等十几秒。主备与冷却的语义由
     * {@link ResilientModel} 承担，与 legacy 的 {@code FailoverModelProvider} 对齐。</p>
     *
     * <p><b>缓存键是组合而不是路由键</b>：熔断状态挂在实例上，必须跨请求共享；而两条路由
     * 配成同一个组合时它们打的是同一个上游，共用一个熔断器才是对的。</p>
     *
     * <p>只有首选才包备用：备用自己再包一层首选会绕成环，而且备用没有「备用的备用」。</p>
     */
    private Model resilientModel(RoutePlan plan) {
        return resilientCache.computeIfAbsent(plan.cacheKey(), ignored -> buildResilient(plan));
    }

    private Model buildResilient(RoutePlan plan) {
        String primaryId = plan.primary();
        Model primary = modelOf(primaryId);
        // 单次调用的无响应超时：与 legacy 的 HttpRequest.timeout 同值
        Duration timeout = aiProperties.getTimeoutSeconds() > 0
                ? Duration.ofSeconds(aiProperties.getTimeoutSeconds())
                : null;
        if (!plan.hasFallback()) {
            return ResilientModel.of(primary, primaryId, timeout);
        }
        String fallbackId = plan.fallback();
        try {
            Model fallback = modelOf(fallbackId);
            return new ResilientModel(primary, primaryId, fallback, fallbackId,
                    aiProperties.getFailoverCooldownSeconds() * 1000L, timeout);
        } catch (RuntimeException e) {
            log.warn("AgentScopeEngine#resilientModel#兜底模型不可用，只用首选 route={} provider={} err={}",
                    plan.key(), fallbackId, e.toString());
            return ResilientModel.of(primary, primaryId, timeout);
        }
    }

    /**
     * 注册工具，按名字排序。
     *
     * <p>顺序必须跨进程稳定：工具定义会进请求体，顺序一变前缀缓存整段作废
     * （实测第二轮命中率从 73.5% 掉到 0）。</p>
     */
    private Toolkit buildToolkit(Agent target) {
        Toolkit toolkit = new Toolkit();
        int argsChars = obs.argsChars();
        int[] schemaChars = {0};
        target.toolNames().stream().sorted().forEach(name -> {
            AgentTool tool = toolRegistry.find(name);
            if (tool == null) {
                log.warn("AgentScopeEngine#buildToolkit#子Agent声明的工具不存在 agent={} tool={}",
                        target.name(), name);
                return;
            }
            ToolBridge bridge = new ToolBridge(tool, argsChars, toolInterceptors,
                    compactionProperties.getUnloadThresholdChars());
            toolkit.registerAgentTool(bridge);
            schemaChars[0] += schemaChars(bridge);
        });
        // 工具定义的体量要跟着 Toolkit 一起缓存：它进的是请求的 tools 字段，
        // 不占任何一条消息，所以 request_chars 必须单独把它补上，否则与 legacy 不可比
        toolkitChars.put(target.name(), schemaChars[0]);
        log.info("AgentScopeEngine#buildToolkit#构建工具集 agent={} 工具数={} schema字符={}",
                target.name(), toolkit.getToolNames().size(), schemaChars[0]);
        return toolkit;
    }

    /** 一个工具在请求体里占的字符数：名字 + 说明 + 参数 Schema 的 JSON 序列化 */
    private int schemaChars(ToolBridge bridge) {
        int sum = 0;
        if (bridge.getName() != null) {
            sum += bridge.getName().length();
        }
        if (bridge.getDescription() != null) {
            sum += bridge.getDescription().length();
        }
        try {
            sum += JSON.toJSONString(bridge.getParameters()).length();
        } catch (RuntimeException e) {
            log.debug("AgentScopeEngine#schemaChars#参数 Schema 序列化失败 tool={}", bridge.getName());
        }
        return sum;
    }

    /**
     * 按 provider 取模型客户端（带缓存，内部持有连接池）。
     *
     * <p>刻意是 {@code protected} 而不是 private：离线测试要换成一个脚本化的假模型，
     * 否则「编排 + 埋点」这条链路只能在有 API Key 的环境里验证，CI 里永远是盲区。</p>
     */
    protected Model modelOf(String providerId) {
        return modelCache.computeIfAbsent(providerId, this::buildModel);
    }

    /** 按 provider 构造模型；OpenAI 兼容协议覆盖 DeepSeek / 硅基流动 / one-api，Ollama 单独一条 */
    private Model buildModel(String providerId) {
        AiProperties.Endpoint endpoint = aiProperties.endpoint(providerId);
        if ("ollama".equals(providerId)) {
            // Ollama 扩展的采样参数走 OllamaOptions 而非 GenerateOptions，这里用服务端默认值：
            // 兜底链路只求「答得出来」，不追求与云端一致的采样细节
            return OllamaChatModel.builder()
                    .baseUrl(endpoint.getBaseUrl())
                    .modelName(endpoint.getModel())
                    // 流式按渠道配置（agent-kit.stream / endpoints.<id>.stream）：
                    // 内网代理缓冲 SSE 或网关不认 stream=true 时关掉，界面体验反而更好
                    .stream(aiProperties.streamOf(providerId))
                    .httpTransport(aiTransport())
                    .build();
        }
        return OpenAIChatModel.builder()
                .apiKey(endpoint.getApiKey())
                .baseUrl(endpoint.getBaseUrl())
                .modelName(endpoint.getModel())
                .stream(aiProperties.streamOf(providerId))
                .generateOptions(generateOptions(endpoint))
                .httpTransport(aiTransport())
                .build();
    }

    /**
     * 懒加载共享传输层：所有 provider 走同一个 OkHttp 连接池。
     *
     * <p>配置取框架默认值（连接 30s / 读 5min / 池 5 连接 × 保活 5min）——要改的只有
     * 「传输层实现从 JDK 换成 OkHttp」这一件事，其余保持与事故前同口径，避免引入新变量。</p>
     */
    private HttpTransport aiTransport() {
        HttpTransport t = aiTransport;
        if (t == null) {
            synchronized (this) {
                if (aiTransport == null) {
                    aiTransport = new OkHttpTransport(HttpTransportConfig.builder().build());
                }
                t = aiTransport;
            }
        }
        return t;
    }

    /**
     * 采样参数。<b>思考开关必须在这里显式透传，不能只靠提示词。</b>
     *
     * <p>{@code PromptTailMiddleware} 里那句「直接给出最终结果」只是提示词层面的引导，
     * 真正关掉思考要靠请求体的 {@code thinking.type=disabled}。这里漏传的后果不是报错：
     * 模型按默认（开思考）跑，每次回复先产出一段推理内容，输出 token 与耗时双增，
     * 而观察数据上只表现为「新引擎成本高」，根因藏得很深。</p>
     *
     * <p>链路是 {@code additionalBodyParams} → 框架的 {@code OpenAIRequest.extraParams}
     * → 原样并入请求体。只在配置显式给了 {@code thinking} 时才发，免得别的网关
     * 因未知字段 400（硅基流动、one-api 之类不一定认这个字段）。</p>
     */
    private GenerateOptions generateOptions(AiProperties.Endpoint endpoint) {
        GenerateOptions.Builder builder = GenerateOptions.builder()
                .temperature(aiProperties.getTemperature())
                .maxTokens(aiProperties.getMaxOutputTokens());
        if (endpoint.getThinking() != null) {
            builder.additionalBodyParam("thinking",
                    Map.of("type", Boolean.TRUE.equals(endpoint.getThinking()) ? "enabled" : "disabled"));
        }
        if (StringUtils.hasText(endpoint.getReasoningEffort())) {
            builder.reasoningEffort(endpoint.getReasoningEffort());
        }
        return builder.build();
    }
}
