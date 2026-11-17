package com.beastmouth.agentkit.agentscope.obs;

import com.beastmouth.agentkit.obs.LlmCallRecorder;
import com.beastmouth.agentkit.obs.ObsHub;
import com.beastmouth.agentkit.obs.RunScope;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatUsage;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型调用的观测钩子：把框架内部的每一次 model call 翻译成 {@code ai_llm_call}。
 *
 * <h3>为什么必须用 Hook，而不是在引擎里包一层</h3>
 * <p>legacy 引擎的模型调用是「我们主动调 {@code provider.chat()}」，所以能在调用点包一层
 * {@code ObservedModelCall}。AgentScope 的 ReAct 循环在框架内部走完「调模型 → 解析工具 →
 * 执行 → 再调模型」，一轮对话可能有 1~5 次调用，引擎只拿到最终结果。
 * <b>回收处必须落在框架暴露的切面上</b>：{@code PRE_REASONING} → {@code POST_REASONING}
 * 恰好是一次完整的 model call，且 POST 事件里的 {@link Msg#getUsage()} 带着
 * input / output / cached / reasoning 四个数，正是成本报表要的。</p>
 *
 * <h3>为什么是 per-request 实例</h3>
 * <p>{@link RunScope} 走 TransmittableThreadLocal，而 Hook 在 Reactor 线程上被回调——
 * 那个线程没有经过 TTL 包装，{@code RunScope.current()} 读出来是 NOOP。
 * 所以 scope 由构造器<b>显式持有</b>，与线程无关。代价是 agent 不能跨请求复用（配合改动见
 * {@code AgentScopeEngine}）。这条比「复用 agent 省一次对象构造」重要得多：
 * 埋点静默失效，报表上会表现成「新引擎没有数据」，而不是报错。</p>
 *
 * <h3>失败路径</h3>
 * <p>模型报错时框架发 {@link ErrorEvent} 而不一定发 POST_REASONING，所以两种收尾都要兜：
 * 拿到 POST 就按 usage 记，只拿到 ERROR 就记一条 {@code status=ERROR}。
 * 两条都走同一个 {@link LlmCallRecorder}，指标与明细不会各记一半。</p>
 */
@Slf4j
public class ObservingHook implements Hook {

    /** 与 legacy 的 purpose 口径一致：ReAct 的每一次 reasoning 都是「决策」 */
    private static final String PURPOSE = "ACT";

    private final RunScope scope;
    private final String providerId;
    private final LlmCallRecorder recorder;

    /**
     * 本子 Agent 的工具定义字符数。
     *
     * <p>legacy 把工具说明写在 system 文本里，所以那部分天然算进 request_chars；
     * 新引擎走原生 {@code tools} 字段，文本不在任何一条 {@code Msg} 里。
     * 不补这一项，两边的「请求体量」会差三倍——不是谁更省，是口径不同。
     * 报表要拿这列做交叉验证，就必须同口径。</p>
     */
    private final int toolChars;

    /** 当前进行中的 model call；ReAct 循环里模型调用是串行的，用单槽即可 */
    private final AtomicReference<Pending> pending = new AtomicReference<>();

    /** 本次调用的首 token 时间戳（nanoTime）；-1 表示还没到 */
    private final AtomicLong firstTokenNanos = new AtomicLong(-1);

    public ObservingHook(RunScope scope, String providerId, LlmCallRecorder recorder) {
        this(scope, providerId, recorder, 0);
    }

    /**
     * @param scope      本次请求的观测上下文（由引擎显式传入，不能靠 TTL）
     * @param providerId 渠道 id（deepseek / ollama），成本按它+模型查价目表
     * @param recorder   记账收口，与 legacy 引擎共用同一份实现
     * @param toolChars  本子 Agent 工具定义（JSON Schema）的字符数，用于与 legacy 的
     *                   「工具写在 system 里」保持同一口径
     */
    public ObservingHook(RunScope scope, String providerId, LlmCallRecorder recorder, int toolChars) {
        this.scope = scope;
        this.providerId = providerId;
        this.recorder = recorder;
        this.toolChars = Math.max(0, toolChars);
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        try {
            if (event instanceof PreReasoningEvent pre) {
                begin(pre);
            } else if (event instanceof ReasoningChunkEvent) {
                markFirstToken();
            } else if (event instanceof PostReasoningEvent post) {
                finish(post);
            } else if (event instanceof ErrorEvent error) {
                fail(error);
            }
        } catch (RuntimeException e) {
            // 埋点是旁路：这里抛异常会让整轮对话失败，绝对不能
            log.debug("ObservingHook#onEvent#埋点异常（不影响对话） type={} err={}",
                    event == null ? null : event.getType(), e.toString());
        }
        return Mono.just(event);
    }

    // ---------------- 四个切面 ----------------

    private void begin(PreReasoningEvent event) {
        String model = event.getModelName();
        int requestChars = charsOf(event) + toolChars;
        // 原文只在本对象里待到 POST_REASONING，随后写进 Langfuse 的 generation Input，不落库；
        // 开关关掉时直接跳过拼接，别白扫一遍上万字的 system + memory
        String requestText = ObsHub.tracing().ioEnabled() ? requestTextOf(event) : null;
        Span span = ObsHub.tracing().startLlm(scope.getSpan(), providerId, model, PURPOSE);

        Pending previous = pending.getAndSet(
                new Pending(scope.nextSeq(), System.nanoTime(), model, requestChars, requestText, span));
        if (previous != null) {
            // 上一次没等到 POST（被中断/异常）——把 span 收掉，否则 Langfuse 里会挂一串不闭合的根
            recorder.closeSpan(previous.span(), "NO_RESULT");
            log.debug("ObservingHook#begin#上一轮调用未收尾，按无结果关闭 seq={}", previous.stepSeq());
        }
        firstTokenNanos.set(-1);
    }

    private void markFirstToken() {
        if (pending.get() == null) {
            return;
        }
        // 流式下每个 token 都会来一次，CAS 只让第一个写进去
        firstTokenNanos.compareAndSet(-1, System.nanoTime());
    }

    private void finish(PostReasoningEvent event) {
        Pending current = pending.getAndSet(null);
        if (current == null) {
            // 事件顺序异常时不要凭空造一行：宁可少一条，也不要把两轮拼成一条
            return;
        }
        Msg message = event.getReasoningMessage();
        ChatUsage usage = message == null ? null : message.getUsage();

        int input = usage == null ? 0 : usage.getInputTokens();
        int output = usage == null ? 0 : usage.getOutputTokens();
        int cached = usage == null ? 0 : usage.getCachedTokens();
        int reasoning = usage == null ? 0 : usage.getReasoningTokens();

        long ttftMs = ttftOf(current);
        if (ttftMs > 0) {
            // 任务级首字：RunScope 只记第一次，与 Controller 里 sink 的打点是同一个语义
            scope.markTtft(ttftMs);
        }

        recorder.record(scope, LlmCallRecorder.Sample.builder()
                .provider(providerId)
                .model(current.model() == null ? "unknown" : current.model())
                .purpose(PURPOSE)
                .stream(1)
                .stepSeq(current.stepSeq())
                .inputTokens(input)
                .outputTokens(output)
                .cachedTokens(cached)
                .reasoningTokens(reasoning)
                .ttftMs(ttftMs)
                .durationMs(millisSince(current.startNanos()))
                .requestChars(current.requestChars())
                .responseChars(textLength(message))
                .requestText(current.requestText())
                .responseText(textOf(message))
                .status("OK")
                .build(), current.span());
    }

    /** 只拿到 ErrorEvent（模型/网络失败）时的收尾 */
    private void fail(ErrorEvent event) {
        Pending current = pending.getAndSet(null);
        if (current == null) {
            return;
        }
        recorder.record(scope, LlmCallRecorder.Sample.builder()
                .provider(providerId)
                .model(current.model() == null ? "unknown" : current.model())
                .purpose(PURPOSE)
                .stream(1)
                .stepSeq(current.stepSeq())
                .inputTokens(0)
                .outputTokens(0)
                .cachedTokens(0)
                .reasoningTokens(0)
                .ttftMs(0)
                .durationMs(millisSince(current.startNanos()))
                .requestChars(current.requestChars())
                .responseChars(0)
                .requestText(current.requestText())
                .responseText(event.getError() == null ? null : event.getError().toString())
                .status("ERROR")
                .errorCode(errorCode(event.getError()))
                .build(), current.span());
    }

    // ---------------- 小工具 ----------------

    private long ttftOf(Pending current) {
        long first = firstTokenNanos.get();
        return first < 0 ? 0 : (first - current.startNanos()) / 1_000_000;
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String errorCode(Throwable error) {
        if (error == null) {
            return "UNKNOWN";
        }
        String name = error.getClass().getSimpleName();
        return name == null || name.isBlank() ? "UNKNOWN" : name;
    }

    /**
     * 请求侧字符数：估算 token 用。
     *
     * <p><b>必须把 system、memory、本轮输入三段都算上。</b>只取
     * {@code getInputMessages()} 会得到一个荒谬的小数——实测同一轮对话 legacy 记
     * 17,194 字符，而这里只记到 27 字符（那是本轮工具结果摘要的长度，真正的提示词
     * 在前两段里）。两个引擎的 {@code request_chars} 落在同一张报表上，一个按全量算、
     * 一个按增量算，等于这一列彻底不可比。</p>
     *
     * <p>三段之间可能有重叠（框架把这轮输入同时放在 memory 与 inputMessages 里），
     * 所以按对象身份去重：按内容去重会把「用户连说两句一样的话」也算成一句。</p>
     */
    private static int charsOf(PreReasoningEvent event) {
        Set<Msg> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        int sum = 0;

        Msg system = event.getSystemMessage();
        if (system != null && seen.add(system)) {
            sum += lengthOf(system);
        }
        try {
            List<Msg> remembered = event.getMemory() == null ? null : event.getMemory().getMessages();
            if (remembered != null) {
                for (Msg message : remembered) {
                    if (message != null && seen.add(message)) {
                        sum += lengthOf(message);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("ObservingHook#charsOf#读 memory 失败，只统计本轮输入 err={}", e.toString());
        }
        List<Msg> inputs = event.getInputMessages();
        if (inputs != null) {
            for (Msg message : inputs) {
                if (message != null && seen.add(message)) {
                    sum += lengthOf(message);
                }
            }
        }
        return sum;
    }

    /** 一条消息的可见文本长度（文本块），不含角色等协议开销 */
    private static int lengthOf(Msg message) {
        int sum = 0;
        List<ContentBlock> blocks = message.getContent();
        if (blocks == null) {
            return 0;
        }
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text && text.getText() != null) {
                sum += text.getText().length();
            }
        }
        return sum;
    }

    /** 响应侧字符数：同样服务于「网关没给 usage」时的估算 */
    private static int textLength(Msg message) {
        return lengthOf(message);
    }

    /**
     * 请求侧原文，与 {@link #charsOf} 同口径（system / memory / 本轮输入三段 + 对象身份去重），
     * 只是把长度换成文本，供 Langfuse 的 generation Input 回放。不落业务库。
     */
    private static String requestTextOf(PreReasoningEvent event) {
        Set<Msg> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        StringBuilder sb = new StringBuilder();
        appendText(sb, "system", event.getSystemMessage(), seen);
        try {
            List<Msg> remembered = event.getMemory() == null ? null : event.getMemory().getMessages();
            if (remembered != null) {
                for (Msg message : remembered) {
                    appendText(sb, "memory", message, seen);
                }
            }
        } catch (RuntimeException e) {
            log.debug("ObservingHook#requestTextOf#读 memory 失败 err={}", e.toString());
        }
        List<Msg> inputs = event.getInputMessages();
        if (inputs != null) {
            for (Msg message : inputs) {
                appendText(sb, "input", message, seen);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void appendText(StringBuilder sb, String label, Msg message, Set<Msg> seen) {
        if (message == null || !seen.add(message)) {
            return;
        }
        String text = textOf(message);
        if (text != null) {
            sb.append('[').append(label).append("] ").append(text).append('\n');
        }
    }

    /** 一条消息的可见文本，与 {@link #textLength} 同口径；没有文本块时返回 null */
    private static String textOf(Msg message) {
        if (message == null) {
            return null;
        }
        List<ContentBlock> blocks = message.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text && text.getText() != null) {
                sb.append(text.getText());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 一次进行中的调用 */
    private record Pending(int stepSeq, long startNanos, String model, int requestChars,
                           String requestText, Span span) {
    }
}
