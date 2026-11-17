package com.beastmouth.agentkit.provider;

import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.model.ModelReply;
import com.beastmouth.agentkit.obs.ObservedModelCall;
import com.beastmouth.agentkit.support.AgentKitException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 主备切换：首选提供方失败时自动落到备份提供方。
 *
 * <p>云端模型会有余额不足、限流、网络抖动这些「不是 bug 但确实会挂」的失败；
 * 本地 Ollama 常驻，用来兜底比直接把 500 抛给用户好。降级只发生在**第一个字还没吐出去**之前，
 * 已经流了一半再重试会让用户看到两遍开头，所以那种情况直接失败。</p>
 *
 * <h3>降级必须可观测（2026-11-03 事故教训）</h3>
 * <p>首选的失败在这里被吞掉，如果直接调 {@code fallback.chat()}，观测收口
 * （{@link ObservedModelCall}）只看得到最终结果——指标里永远只有 {@code status=OK}，
 * 错误率告警是瞎的；而且降级调用的 token 被记到首选头上，成本口径也被污染。
 * 所以降级路径必须显式走观测：首选失败记 {@code ERROR}，兜底调用以
 * {@code purpose=fallback} 完整入账（provider/model/耗时都是它自己的）。</p>
 *
 * <h3>过载熔断</h3>
 * <p>「失败→立刻降级」省得掉重试，省不掉首选挂住连接的那几十秒（2026-11-03 DeepSeek
 * 过载，单请求 18~25 秒才吐 503）。冷却期内新请求直接走兜底，不再撞首选——
 * 把过载窗口里「每轮都白等一次」变成「只在熔断那一刻等一次」。冷却过了自动恢复首选。</p>
 *
 * <p><b>状态存活的前提</b>：本实例必须被复用（Registry 按主备组合缓存），
 * 每次调用 new 一个的话熔断窗口永远为零。</p>
 */
@Slf4j
public class FailoverModelProvider implements ModelProvider {

    /** 降级调用在观测里的 purpose 标记：llm 行 / trace 上与正常调用区分开 */
    public static final String FALLBACK_PURPOSE = "fallback";

    private final ModelProvider primary;
    private final ModelProvider fallback;

    /** 可空：观测收口不存在时（单测、观测关闭）直连兜底，行为与旧版一致 */
    private final ObservedModelCall observer;

    /** 熔断冷却毫秒数，0 表示关闭熔断 */
    private final long cooldownMillis;

    /** 首选被熔断到什么时候。volatile：对话线程池多线程读写 */
    private volatile long primaryBlockedUntil;

    public FailoverModelProvider(ModelProvider primary, ModelProvider fallback) {
        this(primary, fallback, null, 0);
    }

    public FailoverModelProvider(ModelProvider primary, ModelProvider fallback,
                                 ObservedModelCall observer, long cooldownMillis) {
        this.primary = primary;
        this.fallback = fallback;
        this.observer = observer;
        this.cooldownMillis = cooldownMillis;
    }

    @Override
    public String id() {
        return primary.id();
    }

    /**
     * 流式与否跟着首选走，降级不改变这个行为。
     *
     * <p>若按当前实际生效的渠道取，就会出现「同一条链路前半段流式、降级后整段蹦出来」——
     * 前端是按 SSE 渲染的，突然收一个完整大块，效果比一开始就不流更怪。</p>
     */
    @Override
    public boolean streaming() {
        return primary.streaming();
    }

    @Override
    public ModelReply chat(List<ChatMessage> messages, boolean jsonMode) {
        if (blocked()) {
            return chatViaFallback(messages, jsonMode);
        }
        long t0 = System.nanoTime();
        try {
            return primary.chat(messages, jsonMode);
        } catch (AgentKitException e) {
            demote(t0, e);
            return chatViaFallback(messages, jsonMode);
        }
    }

    @Override
    public ModelReply stream(List<ChatMessage> messages, Consumer<String> onDelta) {
        if (blocked()) {
            return streamViaFallback(messages, onDelta);
        }
        AtomicBoolean emitted = new AtomicBoolean(false);
        long t0 = System.nanoTime();
        try {
            return primary.stream(messages, chunk -> {
                emitted.set(true);
                if (onDelta != null) {
                    onDelta.accept(chunk);
                }
            });
        } catch (AgentKitException e) {
            if (emitted.get()) {
                // 已经往外吐过字了，再切会让用户看到重复的开头，只能如实失败
                throw e;
            }
            demote(t0, e);
            return streamViaFallback(messages, onDelta);
        }
    }

    // ---------------- 内部 ----------------

    private boolean blocked() {
        return cooldownMillis > 0 && System.currentTimeMillis() < primaryBlockedUntil;
    }

    /** 首选失败：进冷却 + 把被吞掉的失败记进观测（ERROR 计数与降级事件） */
    private void demote(long t0, RuntimeException cause) {
        primaryBlockedUntil = System.currentTimeMillis() + cooldownMillis;
        long durationMs = (System.nanoTime() - t0) / 1_000_000;
        log.warn("FailoverModelProvider#demote#首选失败，降级到 {} primary={} 耗时={}ms err={} 冷却={}s",
                fallback.id(), primary.id(), durationMs, cause.getMessage(), cooldownMillis / 1000);
        if (observer != null) {
            observer.primaryFailed(primary, durationMs, cause);
        }
    }

    private ModelReply chatViaFallback(List<ChatMessage> messages, boolean jsonMode) {
        // 降级调用必须走观测收口：provider/model/耗时/token 都记在兜底自己名下
        return observer == null
                ? fallback.chat(messages, jsonMode)
                : observer.chat(fallback, messages, jsonMode, FALLBACK_PURPOSE);
    }

    private ModelReply streamViaFallback(List<ChatMessage> messages, Consumer<String> onDelta) {
        return observer == null
                ? fallback.stream(messages, onDelta)
                : observer.stream(fallback, messages, onDelta, FALLBACK_PURPOSE);
    }
}
