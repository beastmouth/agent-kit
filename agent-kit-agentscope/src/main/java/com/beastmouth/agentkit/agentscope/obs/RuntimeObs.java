package com.beastmouth.agentkit.agentscope.obs;

import com.beastmouth.agentkit.agent.ChatSink;
import com.beastmouth.agentkit.context.RunContext;
import com.beastmouth.agentkit.context.TurnHook;
import com.beastmouth.agentkit.obs.LlmCallRecorder;
import com.beastmouth.agentkit.obs.ObsProperties;
import com.beastmouth.agentkit.obs.ObsWriter;
import com.beastmouth.agentkit.obs.RunScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 引擎侧的观测装配：把「一个子 Agent 跑一轮」的生命周期与 legacy 对齐。
 *
 * <h3>为什么要有这一层</h3>
 * <p>legacy 引擎的 run 生命周期写在 {@code SubAgentRunner.run} 里：轮次钩子 →
 * {@code RunScope.begin} → 执行 → {@code ObsWriter.finish} → 收钩子 → {@code RunScope.clear}。
 * 新引擎如果不做同样的事，最直接的后果不是「少几条数据」，而是：
 * <ul>
 *   <li>{@code ai_run} 表完全没有新引擎的行——成本、成功率、空结果率全部看不见；</li>
 *   <li>{@link TurnHook} 没被调用，业务侧在该时机做的数据预取与作用域开合整体失效，
 *       而工具那边又依赖它——表现为「结果莫名其妙地是空的」。</li>
 * </ul>
 * 两件事都是静默的，所以必须在这层显式补齐，而不是指望调用方记得。</p>
 *
 * <h3>为什么 finish 里要清 RunScope</h3>
 * <p>{@link RunScope} 是线程相关的（TransmittableThreadLocal），线程池复用时不清理
 * 就会把上一轮的会话 id 带到下一轮。这里统一在 finally 收口，调用方不需要知道有几个
 * ThreadLocal。</p>
 *
 * <h3>DISABLED</h3>
 * <p>单测与 PoC 场景不装配观测组件，用 {@link #DISABLED}：所有方法退化为直通，
 * 既不建 scope 也不落库，行为与「没有这套东西」完全一致。</p>
 */
@Slf4j
@Component
public class RuntimeObs {

    /** 无容器/无观测时使用：不建 scope、不落库、不打点 */
    public static final RuntimeObs DISABLED = new RuntimeObs(null, new ObsProperties(), null, List.of());

    private final ObsWriter obsWriter;
    private final ObsProperties obsProperties;
    private final LlmCallRecorder recorder;
    private final List<TurnHook> turnHooks;

    @Autowired
    public RuntimeObs(ObsWriter obsWriter, ObsProperties obsProperties,
                      LlmCallRecorder recorder, List<TurnHook> turnHooks) {
        this.obsWriter = obsWriter;
        this.obsProperties = obsProperties;
        this.recorder = recorder;
        this.turnHooks = turnHooks == null ? List.of() : List.copyOf(turnHooks);
    }

    public boolean enabled() {
        return obsWriter != null;
    }

    /**
     * 进入一个子 Agent 的执行：开轮次钩子 + 开 run。
     *
     * <p>顺序沿用 legacy：钩子先开（业务可能要预取数据），再开 run——
     * 这样预取产生的耗时不计入本轮 run，两条链路的日志时间线也可比。</p>
     */
    public RunScope begin(String agentName, String userInput) {
        for (TurnHook hook : turnHooks) {
            try {
                hook.onTurnStart(userInput);
            } catch (RuntimeException e) {
                // 钩子挂掉不能连累对话，与 legacy 的处理一致
                log.warn("RuntimeObs#begin#轮次钩子失败 hook={} err={}",
                        hook.getClass().getSimpleName(), e.toString());
            }
        }
        if (!enabled()) {
            return RunScope.current();
        }
        return RunScope.begin(agentName, channel(), userInput, obsProperties.getPreviewChars());
    }

    /**
     * 收尾：落一批（run + llm_call + tool_call），收钩子，清 TTL。
     *
     * <p>不抛异常：观测收尾失败不能变成用户看到 500。</p>
     */
    public void finish(RunScope scope, String status, String errorCode) {
        try {
            if (enabled() && scope != null && !scope.noop()) {
                obsWriter.finish(scope, status, errorCode);
            }
        } catch (RuntimeException e) {
            log.warn("RuntimeObs#finish#收尾失败（不影响业务） err={}", e.toString());
        } finally {
            // 倒序收尾：后开的先关，和 try-with-resources 的语义一致
            for (int i = turnHooks.size() - 1; i >= 0; i--) {
                try {
                    turnHooks.get(i).onTurnEnd();
                } catch (RuntimeException e) {
                    log.warn("RuntimeObs#finish#轮次收尾失败 hook={} err={}",
                            turnHooks.get(i).getClass().getSimpleName(), e.toString());
                }
            }
            RunScope.clear();
        }
    }

    /**
     * 给 sink 补一次首字打点。
     *
     * <p>入口的 sink 里通常已经打过一次，但它依赖 {@code RunScope.current()}——
     * 流式回调来自 Reactor 线程，TTL 不传递，那里读到的往往是 NOOP。
     * 这里用<b>显式持有的 scope</b> 再打一次：{@link RunScope#markTtft} 只认可第一次，
     * 所以两处并存不会重复计时，哪个先到算哪个。</p>
     */
    public ChatSink ttftAware(ChatSink sink, RunScope scope) {
        if (sink == null || scope == null || scope.noop()) {
            return sink;
        }
        return new ChatSink() {
            @Override
            public void status(String stage, String label) {
                sink.status(stage, label);
            }

            @Override
            public void tool(String name, boolean success, String summary) {
                sink.tool(name, success, summary);
            }

            @Override
            public void delta(String text) {
                if (text != null && !text.isEmpty()) {
                    scope.markTtft(scope.elapsedMs());
                }
                sink.delta(text);
            }
        };
    }

    /** 模型调用记账收口；DISABLED 时返回 null（调用方据此不注册 Hook） */
    public LlmCallRecorder recorder() {
        return recorder;
    }

    /** 工具入参落库截断长度，工具适配器建一次就固定下来 */
    public int argsChars() {
        return enabled() ? obsProperties.getArgsChars() : 1024;
    }

    /**
     * 请求来自哪个通道：取 {@link RunContext} 的 {@code channel} 标签，缺省 {@code WEB}。
     *
     * <p>多入口系统（网页 / IM / 开放 API）靠这个在一张报表上区分来源；不设也不影响功能。</p>
     */
    private static String channel() {
        Object value = RunContext.label("channel");
        if (value == null || value.toString().isBlank()) {
            return "WEB";
        }
        return value.toString();
    }
}
