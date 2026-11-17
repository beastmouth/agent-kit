package com.beastmouth.agentkit.agentscope.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import com.beastmouth.agentkit.support.AgentKitException;
import com.beastmouth.agentkit.support.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 带熔断冷却的主备切换。
 *
 * <h3>为什么框架自带的 fallbackModel 不够</h3>
 * <p>框架的失败转移只做「这一次失败 → 这一次换备用」，<b>不记住失败</b>。过载窗口里
 * 上游不是挂一次，是连续几十秒都在超时：每个新请求仍会先撞首选、再等它超时、再换备用。
 * 实测过载时单请求 18~25 秒才吐 503——每轮对话白等二十秒，用户体感就是「助手哑了」。
 * legacy 引擎补的就是这个：首选一失败就进冷却，冷却期内新请求直接走备用，
 * 只在那一次降级时付代价。</p>
 *
 * <p>所以这里包一层 {@link Model}：熔断状态跟着实例走，而实例由
 * {@code AgentScopeEngine#resilientModel()} 持有，跨请求共享——如果每次请求新建一个，
 * 冷却窗口永远是 0，等于没做。</p>
 *
 * <h3>已经吐字就不能重放</h3>
 * <p>流式场景下，如果第一个增量已经推给用户，再从备用重订阅会让用户看到两遍开头。
 * 所以用 {@code emitted} 判断：吐过字就如实失败，没吐过才降级。legacy 的
 * {@code FailoverModelProvider} 是同一个判断。</p>
 *
 * <p>除了降级，<b>上层对同一条流重订阅</b>也会重放。{@code primaryAttempt} 的 defer 里
 * 因此也检查 {@code emitted}。但框架自带的模型级重试（{@code ModelUtils} 的
 * {@code retryWhen}，默认 {@code maxAttempts=3}）发生在 ChatModel 内部，本类看不到，
 * 拦不住——这是已知限制，见 {@code docs/08-引擎选型.md}。</p>
 *
 * <h3>瞬态传输错误先重试首选一次，再考虑降级</h3>
 * <p>踩过的坑：首选的 JDK 连接池复用到闲置十几小时的死连接，15ms 即
 * {@code EOFException}——这属于「换一条连接就好」的瞬态故障，但当时直接降级，
 * 整轮对话落到本地 8B 模型上，慢且产出内容幻觉。
 * 所以对「吐字前 + cause 链里有 {@link java.io.IOException} 或超时」的错误
 * 先重试首选一次；重试也失败（或非瞬态错误）才降级。非瞬态（4xx、鉴权、
 * 参数问题）重试毫无意义，仍然直接降级。</p>
 *
 * <h3>降级必须留痕</h3>
 * <p>首选失败被吞掉之后，如果只在最终结果上看，指标永远是 OK、错误率永远偏低。
 * 这里每个降级点都打一条 WARN（含首选/备用、耗时、错误、冷却秒数），
 * 采集到日志平台就能算降级率。</p>
 */
@Slf4j
public class ResilientModel implements Model {

    private final Model primary;
    private final Model fallback;
    private final String primaryId;
    private final String fallbackId;
    private final long cooldownMillis;

    /**
     * 单次模型调用的无响应超时。
     *
     * <p><b>不设就是没有上限</b>：框架的模型客户端不像 legacy 的
     * {@code OpenAiCompatibleProvider} 那样带 {@code HttpRequest.timeout}，
     * 上游卡住时这条流会一直挂着；外层就算有总超时，也是按
     * {@code timeoutSeconds × (maxSteps+1)} 算的，几十分钟后才断——用户早走了，
     * 线程和连接还占着。</p>
     *
     * <p>用「元素间隔」语义（Reactor 的 {@code timeout} 默认行为）：流式响应里
     * 只要还在吐字就不断开，连续 {@code timeout} 没有任何增量才判失败——
     * 这比「整轮总时长」更贴合真实故障：卡住的表现就是「不动了」。</p>
     */
    private final Duration responseTimeout;

    /** 首选被熔断到什么时候（epoch millis）。volatile：多个请求线程并发读写 */
    private final AtomicLong blockedUntil = new AtomicLong(0);

    public ResilientModel(Model primary, String primaryId, Model fallback, String fallbackId, long cooldownMillis) {
        this(primary, primaryId, fallback, fallbackId, cooldownMillis, null);
    }

    public ResilientModel(Model primary, String primaryId, Model fallback, String fallbackId,
                          long cooldownMillis, Duration responseTimeout) {
        this.primary = primary;
        this.primaryId = primaryId;
        this.fallback = fallback;
        this.fallbackId = fallbackId;
        this.cooldownMillis = Math.max(0, cooldownMillis);
        this.responseTimeout = responseTimeout;
    }

    /**
     * 没有备用时的退化形态：直连首选，行为与包装前完全一致——除了超时。
     *
     * <p>超时不是「主备切换」的附属品，它是所有调用的基本保护，所以无备用时也保留。</p>
     */
    public static ResilientModel of(Model primary, String primaryId) {
        return new ResilientModel(primary, primaryId, null, null, 0);
    }

    public static ResilientModel of(Model primary, String primaryId, Duration responseTimeout) {
        return new ResilientModel(primary, primaryId, null, null, 0, responseTimeout);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        boolean hasFallback = fallback != null && cooldownMillis > 0;
        if (hasFallback && blocked()) {
            log.debug("ResilientModel#stream#首选仍在冷却，直接走备用 primary={} fallback={}",
                    primaryId, fallbackId);
            return withTimeout(fallback.stream(messages, tools, options));
        }

        AtomicBoolean emitted = new AtomicBoolean(false);
        long startedAt = System.nanoTime();
        return primaryAttempt(messages, tools, options, emitted)
                .onErrorResume(error -> {
                    if (emitted.get()) {
                        // 用户已经看到开头了，再切会看到两遍，只能如实失败
                        log.warn("ResilientModel#stream#已开始输出，不降级 primary={} err={}",
                                primaryId, error.toString());
                        return Flux.error(error);
                    }
                    if (isTransientTransportError(error)) {
                        log.warn("ResilientModel#retry#首选瞬态失败，重试一次再考虑降级 primary={} err={}",
                                primaryId, error.toString());
                        return primaryAttempt(messages, tools, options, emitted)
                                .onErrorResume(error2 -> afterPrimaryGaveUp(error2, emitted,
                                        startedAt, hasFallback, messages, tools, options));
                    }
                    return afterPrimaryGaveUp(error, emitted,
                            startedAt, hasFallback, messages, tools, options);
                });
    }

    /**
     * 一次首选尝试：defer 保证重订阅时真的重新调用底层模型，而不是重放缓存。
     *
     * <p><b>但重订阅前要先看有没有吐过字。</b>流式是「一条流一次性消费」，重订阅不是断点续传：
     * 一旦有增量推给用户，重订阅就会把同样的开头再推一遍。</p>
     *
     * <p>{@code emitted} 是 {@link #stream} 方法内的局部变量——<b>同一次 stream() 返回的
     * Flux 被重订阅时是同一个对象，而 ReAct 的下一轮会重新调用 stream() 拿到新的对象</b>，
     * 所以这里能拦住「上层对同一条流重订阅」，又不会误伤「多步推理的正常多次调用」。</p>
     *
     * <p><b>拦不住框架自带的模型级重试</b>：{@code ModelUtils} 的 {@code retryWhen} 在
     * ChatModel 内部按 {@code GenerateOptions.executionConfig} 生效（默认 {@code maxAttempts=3}），
     * 本类只看到「一次 stream() 调用」，等它抛出时内部已经重放过三次。
     * 实测 {@code ReActAgent.Builder#modelExecutionConfig} 改不动它。
     * 所以：上游断流时用户会看到正文重复，这是已知限制，见 {@code docs/08-引擎选型.md}。</p>
     */
    private Flux<ChatResponse> primaryAttempt(List<Msg> messages, List<ToolSchema> tools,
                                              GenerateOptions options, AtomicBoolean emitted) {
        return Flux.defer(() -> {
                    if (emitted.get()) {
                        log.warn("ResilientModel#stream#已经输出过正文，拒绝重放（框架级重试） primary={}",
                                primaryId);
                        return Flux.<ChatResponse>error(AgentKitException.of(
                                ErrorCode.MODEL_UNAVAILABLE, "流式输出已开始，不再重试"));
                    }
                    return withTimeout(primary.stream(messages, tools, options));
                })
                .doOnNext(response -> emitted.set(true));
    }

    /** 首选彻底放弃（重试后仍失败 / 非瞬态 / 吐字后）：有备用则降级，没有则如实抛出 */
    private Flux<ChatResponse> afterPrimaryGaveUp(Throwable error, AtomicBoolean emitted,
                                                  long startedAt, boolean hasFallback,
                                                  List<Msg> messages, List<ToolSchema> tools,
                                                  GenerateOptions options) {
        if (emitted.get()) {
            log.warn("ResilientModel#retry#重试已开始输出，不降级 primary={} err={}",
                    primaryId, error.toString());
            return Flux.error(error);
        }
        if (!hasFallback) {
            return Flux.error(error);
        }
        demote(startedAt, error);
        return withTimeout(fallback.stream(messages, tools, options));
    }

    /**
     * 瞬态传输错误判定：cause 链里出现 {@link java.io.IOException}（含 EOF/连接重置/
     * 连接超时）或 {@link java.util.concurrent.TimeoutException}。
     *
     * <p>框架会把底层异常包两三层（{@code OpenAIException → HttpTransportException → EOFException}），
     * 但 cause 链完整保留，沿链走一遍即可。消息匹配不作为依据——太脆。</p>
     */
    private static boolean isTransientTransportError(Throwable error) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < 10; depth++) {
            if (t instanceof java.io.IOException || t instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** 加上无响应超时；没配就原样返回，不给测试和无观测场景添麻烦 */
    private Flux<ChatResponse> withTimeout(Flux<ChatResponse> flux) {
        return responseTimeout == null ? flux : flux.timeout(responseTimeout);
    }

    @Override
    public String getModelName() {
        return primary.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return primary.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return primary.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return primary.getContextWindowSize();
    }

    private boolean blocked() {
        return System.currentTimeMillis() < blockedUntil.get();
    }

    private void demote(long startedAtNanos, Throwable error) {
        blockedUntil.set(System.currentTimeMillis() + cooldownMillis);
        long durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
        log.warn("ResilientModel#demote#首选失败，降级到 {} primary={} 耗时={}ms 冷却={}s err={}",
                fallbackId, primaryId, durationMs, cooldownMillis / 1000, error.toString());
    }
}
