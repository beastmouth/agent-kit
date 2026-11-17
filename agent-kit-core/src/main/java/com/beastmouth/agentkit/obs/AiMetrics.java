package com.beastmouth.agentkit.obs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 指标的收口（P1）。
 *
 * <h3>为什么指标在这里而不是在业务代码里散着写</h3>
 * <p>指标名字是<b>对外契约</b>：看板、告警、SLO 都按名字查。散着写的结果是同一个含义出现
 * {@code ai_run_total} / {@code agent_runs} / {@code agentRunCount} 三个名字，看板上永远对不上。
 * 所以名字只在这里出现一次，业务侧只调方法。</p>
 *
 * <h3>基数纪律（这条比代码本身重要）</h3>
 * <p>标签只允许低基数枚举：{@code agent / provider / model / status / tool}。
 * <b>绝不放 subjectId / sessionId / traceId</b>——一个调用方一个标签，Prometheus 的时序数会按
 * 用户数线性膨胀，几天就能把内存打爆。用户维度的聚合去 MySQL 做（那里有索引、能 join）。</p>
 *
 * <h3>容错</h3>
 * <p>所有方法自己吞异常：指标是旁路，<b>指标代码抛异常导致对话失败是绝对不能接受的</b>。
 * 容器里拿不到 registry（单测、CLI 场景）时退化为 {@link SimpleMeterRegistry}，只记录不外发。</p>
 */
@Slf4j
@Component
public class AiMetrics {

    /** 无 registry 时的兜底：只累加在内存里，不导出，方法签名与正式环境完全一致 */
    private static final AiMetrics NOOP = new AiMetrics(new SimpleMeterRegistry());

    private final MeterRegistry registry;
    private final AtomicInteger queueDepth = new AtomicInteger();

    /**
     * 主构造器。必须显式标 {@code @Autowired}：这个类有两个构造器（另一个是给单测直接传
     * registry 的包级构造器），Spring 在「多个构造器且无 @Autowired」时会退回找无参构造器，
     * 找不到就抛 {@code NoSuchMethodException: AiMetrics.<init>()} —— 编译和单测全绿，
     * 只有真启动才炸（1.0.9 首次部署就踩到了）。
     */
    @Autowired
    public AiMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** 包内可见：测试直接塞一个 SimpleMeterRegistry，不用起 Spring 容器 */
    AiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    static AiMetrics noop() {
        return NOOP;
    }

    // ---------------- 任务级 ----------------

    /** 一次任务结束：总数 + 耗时 + 成本 + token + 步数，一次调用全记 */
    public void runFinished(RunRow row, long durationMs) {
        if (row == null) {
            return;
        }
        try {
            String agent = tag(row.getAgentName());
            String status = tag(row.getStatus());
            counter("ai_run_total", "agent", agent, "status", status).increment();

            Timer.builder("ai_run_duration")
                    .description("任务端到端耗时")
                    .tag("agent", agent)
                    .register(registry)
                    .record(durationMs, TimeUnit.MILLISECONDS);

            if (row.getCostCny() != null && row.getCostCny().signum() > 0) {
                counter("ai_run_cost_cny_total", "agent", agent)
                        .increment(row.getCostCny().doubleValue());
            }
            addTokens("agent", agent, row.getInputTokens(), row.getOutputTokens());

            DistributionSummary.builder("ai_run_steps")
                    .description("一次任务的模型决策轮数")
                    .baseUnit("steps")
                    .tag("agent", agent)
                    .register(registry)
                    .record(nz(row.getStepCount()));

            if (row.getTtftMs() != null && row.getTtftMs() > 0) {
                Timer.builder("ai_run_ttft")
                        .description("任务首字耗时")
                        .tag("agent", agent)
                        .register(registry)
                        .record(row.getTtftMs(), TimeUnit.MILLISECONDS);
            }
            if (nz(row.getEmptyResult()) == 1) {
                counter("ai_empty_result_total", "agent", agent).increment();
            }
            if (nz(row.getParseFail()) == 1) {
                counter("ai_parse_fail_total", "agent", agent).increment();
            }
        } catch (RuntimeException e) {
            log.debug("AiMetrics#runFinished#记录失败 err={}", e.toString());
        }
    }

    // ---------------- 模型调用 ----------------

    public void llmCall(String provider, String model, String status,
                        int inputTokens, int outputTokens, double costCny,
                        long durationMs, long ttftMs) {
        try {
            String p = tag(provider);
            String m = tag(model);
            counter("ai_llm_call_total", "provider", p, "model", m, "status", tag(status)).increment();

            Timer.builder("ai_llm_duration")
                    .description("单次模型调用耗时")
                    .tag("provider", p)
                    .register(registry)
                    .record(durationMs, TimeUnit.MILLISECONDS);

            if (ttftMs > 0) {
                Timer.builder("ai_llm_ttft")
                        .description("单次模型调用首字耗时")
                        .tag("provider", p)
                        .register(registry)
                        .record(ttftMs, TimeUnit.MILLISECONDS);
            }
            addTokens("provider", p, inputTokens, outputTokens);
            if (costCny > 0) {
                counter("ai_llm_cost_cny_total", "provider", p, "model", m).increment(costCny);
            }
        } catch (RuntimeException e) {
            log.debug("AiMetrics#llmCall#记录失败 err={}", e.toString());
        }
    }

    public void fallback(String provider) {
        safe(() -> counter("ai_fallback_total", "provider", tag(provider)).increment());
    }

    // ---------------- 工具调用 ----------------

    public void toolCall(String tool, boolean success, Integer rows, long durationMs) {
        try {
            counter("ai_tool_call_total", "tool", tag(tool), "status", success ? "OK" : "FAIL").increment();
            Timer.builder("ai_tool_duration")
                    .description("单次工具调用耗时")
                    .tag("tool", tag(tool))
                    .register(registry)
                    .record(durationMs, TimeUnit.MILLISECONDS);
            if (rows != null && rows == 0) {
                counter("ai_tool_zero_rows_total", "tool", tag(tool)).increment();
            }
        } catch (RuntimeException e) {
            log.debug("AiMetrics#toolCall#记录失败 err={}", e.toString());
        }
    }

    /**
     * 幻觉成功：工具 FAIL 但回复说"已办好了"。
     *
     * <p>方案里把它定成红线——它不是"质量差一点"，而是<b>用户在不知情的情况下丢了数据</b>，
     * 所以单独一个计数器，告警阈值是 {@code > 0} 而不是百分比。</p>
     */
    public void hallucinatedSuccess(String agent) {
        safe(() -> counter("ai_hallucinated_success_total", "agent", tag(agent)).increment());
    }

    // ---------------- 观测自身 ----------------

    public void obsDropped() {
        safe(() -> counter("ai_obs_queue_dropped_total").increment());
    }

    public void obsWriteFailed(String table) {
        safe(() -> counter("ai_obs_write_failed_total", "table", tag(table)).increment());
    }

    /** 队列深度：注册成 gauge，Prometheus 抓取时读当前值 */
    public void bindQueueDepth(java.util.function.IntSupplier supplier) {
        try {
            Gauge.builder("ai_obs_queue_depth", supplier, s -> s.getAsInt())
                    .description("观测写队列当前积压条数")
                    // 必须强引用：Micrometer 的 Gauge 默认对被测对象持弱引用，
                    // 传进来的 lambda（queue::size）随时可能被 GC 掉，之后抓到的值就是 NaN
                    // ——面板上一片空白，看着像"没数据"，其实是对象没了。
                    .strongReference(true)
                    .register(registry);
        } catch (RuntimeException e) {
            log.debug("AiMetrics#bindQueueDepth#注册失败 err={}", e.toString());
        }
    }

    // ---------------- 质量侧 ----------------

    public void evalScore(String metric, String label) {
        safe(() -> counter("ai_eval_score_total", "metric", tag(metric), "label", tag(label)).increment());
    }

    public void judgeSkipped(String reason) {
        safe(() -> counter("ai_eval_judge_skipped_total", "reason", tag(reason)).increment());
    }

    public void feedback(String rating) {
        safe(() -> counter("ai_feedback_total", "rating", tag(rating)).increment());
    }

    public void goldenCase(String layer, String label) {
        safe(() -> counter("ai_golden_case_total", "layer", tag(layer), "label", tag(label)).increment());
    }

    // ---------------- 内部 ----------------

    /**
     * 输入里命中前缀缓存的 token 数。
     *
     * <p>单独一个计数器，而不是塞进 {@code ai_tokens_total{kind=input}}：命中率 =
     * {@code ai_cache_hit_tokens_total / ai_tokens_total{kind="input"}}，两者口径必须分离，
     * 否则加起来就是双倍计数。</p>
     */
    public void llmCachedTokens(String provider, int cachedTokens) {
        if (cachedTokens <= 0) {
            return;
        }
        try {
            counter("ai_cache_hit_tokens_total", "provider", tag(provider)).increment(cachedTokens);
        } catch (RuntimeException e) {
            log.debug("AiMetrics#llmCachedTokens#记录失败 err={}", e.toString());
        }
    }

    private void addTokens(String tagKey, String tagValue, Integer input, Integer output) {
        int in = nz(input);
        int out = nz(output);
        if (in > 0) {
            counter("ai_tokens_total", tagKey, tagValue, "kind", "input").increment(in);
        }
        if (out > 0) {
            counter("ai_tokens_total", tagKey, tagValue, "kind", "output").increment(out);
        }
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.debug("AiMetrics#safe#记录失败 err={}", e.toString());
        }
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /** 标签值不允许 null / 空串：Micrometer 遇到 null 会直接 NPE，且报错点在抓取时，极难排查 */
    private static String tag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    MeterRegistry registry() {
        return registry;
    }
}
