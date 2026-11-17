package com.beastmouth.agentkit.obs;

import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.model.ModelReply;
import com.beastmouth.agentkit.provider.ModelProvider;
import com.beastmouth.agentkit.support.Json;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 模型调用的观测收口。
 *
 * <h3>为什么不是一个 {@code @Primary} 的 ModelProvider 装饰器</h3>
 * <p>{@code ModelProviderRegistry} 是按 {@code id()} 索引所有 provider bean 的。如果再注册一个
 * 实现 {@code ModelProvider} 的装饰器，它会成为集合里的一员，{@code id()} 又去问
 * {@code registry.current()}——轻则 Map 键冲突覆盖掉真实 provider，重则自己调自己。
 * <b>所以这里退一步：不改注册表语义，只在调用点上包一层。</b>改动只有
 * {@code SubAgentRunner} 的两行调用，覆盖面和装饰器一样（所有渠道都走它）。</p>
 *
 * <h3>契约</h3>
 * <ul>
 *   <li>记录不改变返回值，<b>异常原样抛出</b>（吞掉异常等于制造"看起来成功"的假象）。</li>
 *   <li>记录自身失败只降级为日志，绝不影响业务。</li>
 * </ul>
 */
@Slf4j
@Component
public class ObservedModelCall {

    private final AiProperties aiProperties;
    private final ObsProperties obsProperties;
    /** 记录口径的唯一实现；本类只负责「在哪一层包」和「什么时候包」 */
    private final LlmCallRecorder recorder;

    /**
     * 主构造器。必须显式标 {@code @Autowired}：本类有两个构造器，
     * Spring 在「多构造器且无标注」时会退回找无参构造器，找不到就启动失败
     * （AiMetrics 已经踩过一次这个坑）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ObservedModelCall(AiProperties aiProperties, LlmCallRecorder recorder, ObsProperties obsProperties) {
        this.aiProperties = aiProperties;
        this.recorder = recorder;
        this.obsProperties = obsProperties;
    }

    /**
     * 兼容既有测试与手工装配（不进容器时自己拼一个无状态 recorder）。
     *
     * <p>保留这个签名不是为了省事：{@code SubAgentRunner} 的单测直接 new 本类，
     * 改签名会让 5 个已经锁住行为的测试一起动，收益为零。</p>
     */
    public ObservedModelCall(AiProperties aiProperties, CostCalculator costCalculator, ObsProperties obsProperties) {
        this(aiProperties, new LlmCallRecorder(costCalculator), obsProperties);
    }

    /**
     * 非流式：一次决策调用。
     *
     * <p><b>NOOP 也要走这条路</b>：没有 run 上下文时（路由调用、定时任务、单测）
     * {@code recordLlmCall} 自己会丢弃明细，但指标仍要出——否则
     * 「路由」这一次模型调用的 token 与成本永远不进 Prometheus，
     * 按单轮估算成本会系统性偏低约 15%~20%，对账时找不到差在哪。
     * 明细表不掺这种无 run_id 的行，成本以指标为准。</p>
     */
    public ModelReply chat(ModelProvider provider, List<ChatMessage> messages, boolean jsonMode, String purpose) {
        RunScope scope = RunScope.current();
        if (!obsProperties.isEnabled()) {
            return provider.chat(messages, jsonMode);
        }
        int seq = scope.nextSeq();
        long t0 = System.nanoTime();
        String model = modelOf(provider);
        Span span = ObsHub.tracing().startLlm(scope.getSpan(), provider.id(), model, purpose);
        try {
            ModelReply reply = provider.chat(messages, jsonMode);
            recorder.record(scope, sample(provider.id(), model, purpose, 0, seq, reply, charsOf(messages), 0, t0,
                    requestText(messages), reply.content()), span);
            return reply;
        } catch (RuntimeException e) {
            recorder.record(scope, failure(provider.id(), model, purpose, 0, seq, charsOf(messages), t0, e,
                    requestText(messages)), span);
            throw e;
        }
    }

    /** 流式：收尾总结用；TTFT 在第一个增量回调里测（NOOP 同 {@link #chat}，只出指标不落明细） */
    public ModelReply stream(ModelProvider provider, List<ChatMessage> messages, Consumer<String> onDelta, String purpose) {
        RunScope scope = RunScope.current();
        if (!obsProperties.isEnabled()) {
            return provider.stream(messages, onDelta);
        }
        int seq = scope.nextSeq();
        long t0 = System.nanoTime();
        String model = modelOf(provider);
        Span span = ObsHub.tracing().startLlm(scope.getSpan(), provider.id(), model, purpose);
        long[] ttft = {-1};
        try {
            ModelReply reply = provider.stream(messages, piece -> {
                if (ttft[0] < 0) {
                    ttft[0] = millisSince(t0);
                    scope.markTtft(ttft[0]);
                }
                onDelta.accept(piece);
            });
            recorder.record(scope, sample(provider.id(), model, purpose, 1, seq, reply, charsOf(messages), ttft[0], t0,
                    requestText(messages), reply.content()), span);
            return reply;
        } catch (RuntimeException e) {
            recorder.record(scope, failure(provider.id(), model, purpose, 1, seq, charsOf(messages), t0, e,
                    requestText(messages)), span);
            throw e;
        }
    }

    // ---------------- 记录 ----------------

    /**
     * 首选提供方失败、即将降级时的记录。
     *
     * <p>这个失败被 {@code FailoverModelProvider} 吞掉了，外层收口只看得到最终结果——
     * 不在这里补一笔的话，指标里永远只有 {@code status=OK}，错误率告警是瞎的
     * （2026-11-03 DeepSeek 503 过载，Prometheus 上却找不到一个 ERROR 点，就是这么来的）。
     * 同时计一条降级事件：{@code ai_fallback_total} 就是「首选不健康」的直接信号。</p>
     */
    public void primaryFailed(ModelProvider provider, long durationMs, RuntimeException cause) {
        try {
            String model = modelOf(provider);
            ObsHub.metrics().llmCall(provider.id(), model, "ERROR", 0, 0, 0D, durationMs, 0);
            ObsHub.metrics().fallback(provider.id());
            log.info("ObservedModelCall#primaryFailed#首选失败入账 provider={} model={} 耗时={}ms err={}",
                    provider.id(), model, durationMs, cause.getMessage());
        } catch (RuntimeException ignored) {
            log.debug("ObservedModelCall#primaryFailed#记录失败 err={}", ignored.toString());
        }
    }

    private LlmCallRecorder.Sample sample(String providerId, String model, String purpose, int stream, int seq,
                                         ModelReply reply, int requestChars, long ttftMs, long t0,
                                         String requestText, String responseText) {
        return LlmCallRecorder.Sample.builder()
                .provider(providerId)
                .model(model)
                .purpose(purpose)
                .stream(stream)
                .stepSeq(seq)
                .inputTokens(reply.promptTokens())
                .outputTokens(reply.outputTokens())
                .cachedTokens(reply.cachedTokens())
                .reasoningTokens(0)
                .ttftMs(ttftMs)
                .durationMs(millisSince(t0))
                .requestChars(requestChars)
                .responseChars(reply.content() == null ? 0 : reply.content().length())
                .requestText(requestText)
                .responseText(responseText)
                .status("OK")
                .build();
    }

    private LlmCallRecorder.Sample failure(String providerId, String model, String purpose, int stream, int seq,
                                           int requestChars, long t0, RuntimeException e, String requestText) {
        return LlmCallRecorder.Sample.builder()
                .provider(providerId)
                .model(model)
                .purpose(purpose)
                .stream(stream)
                .stepSeq(seq)
                .inputTokens(0)
                .outputTokens(0)
                .cachedTokens(0)
                .reasoningTokens(0)
                .ttftMs(0)
                .durationMs(millisSince(t0))
                .requestChars(requestChars)
                .responseChars(0)
                .requestText(requestText)
                .responseText(e.toString())
                .status("ERROR")
                .errorCode(e.getClass().getSimpleName())
                .build();
    }

    private String modelOf(ModelProvider provider) {
        try {
            return aiProperties.endpoint(provider.id()).getModel();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static int charsOf(List<ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int sum = 0;
        for (ChatMessage m : messages) {
            sum += m.content() == null ? 0 : m.content().length();
        }
        return sum;
    }

    /**
     * prompt 原文序列化成 JSON 数组，交给 Langfuse 的 generation Input。
     * <p>只用于上报，不落库、不参与 token 估算；序列化失败返回 null，模型调用照常进行。
     * 长文本由 {@code AiTracing} 按 {@code trace-io-chars} 统一截断。</p>
     */
    private static String requestText(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty() || !ObsHub.tracing().ioEnabled()) {
            return null;
        }
        try {
            return Json.toJson(messages);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
