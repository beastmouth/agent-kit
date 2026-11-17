package com.beastmouth.agentkit.obs;

import com.beastmouth.agentkit.eval.QualityEvaluator;
import com.beastmouth.agentkit.eval.QualityResult;
import com.beastmouth.agentkit.obs.mapper.RunMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 观测数据落库：<b>异步 + 有界 + 丢弃计数</b>。
 *
 * <p>三条铁律（顺序即优先级）：</p>
 * <ol>
 *   <li><b>不在业务线程写库</b>：所有 insert 都在单独 daemon 线程里做，SSE 线程只做一次入队。</li>
 *   <li><b>队列必须有界</b>：满了直接丢并计数，绝不能让观测把对话拖死。丢数据是事故，
 *       卡住对话是更大的事故。</li>
 *   <li><b>不参与业务事务</b>：独立提交，业务回滚不影响失败记录（失败恰恰是最需要留下的）。</li>
 * </ol>
 *
 * <p>P3 起，质量判定也在这里做（{@link QualityEvaluator}）：判定要读工具明细和回复原文，
 * 而这两样刚好处在写库线程手上，顺手算完回写到同一条 insert 里，省掉一次 UPDATE。</p>
 */
@Slf4j
@Component
public class ObsWriter implements AutoCloseable {

    private final ObsProperties properties;
    private final RunMapper mapper;
    private final QualityEvaluator qualityEvaluator;

    /**
     * 这里刻意注入 {@link AiMetrics} 本体，而不是走 {@link ObsHub#metrics()} 静态取。
     *
     * <p>原因：{@code @PostConstruct} 执行时，静态字段可能还是 {@code noop} ——
     * ObsHub 与 ObsWriter 之间没有依赖关系，Spring 建 bean 的顺序不由代码决定。
     * 实测启动日志里 {@code ObsWriter#start} 比 {@code ObsHub#init} 早 51ms，
     * 于是 gauge 被注册到 noop 的临时 registry 上，真实 registry 里永远没有它：
     * Prometheus 查不到 {@code ai_obs_queue_depth}，对应告警静默失效。</p>
     *
     * <p>注入 bean 才有依赖边，Spring 保证先建 AiMetrics 再建 ObsWriter；
     * 运行时那些静态调用不受影响。</p>
     */
    private final AiMetrics metrics;

    private final BlockingQueue<Batch> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong writeFailed = new AtomicLong();

    private volatile boolean running;
    private Thread worker;

    public ObsWriter(ObsProperties properties, RunMapper mapper, QualityEvaluator qualityEvaluator,
                     AiMetrics metrics) {
        this.properties = properties;
        this.mapper = mapper;
        this.qualityEvaluator = qualityEvaluator;
        this.metrics = metrics;
        this.queue = new ArrayBlockingQueue<>(Math.max(properties.getQueueCapacity(), 1_000));
    }

    @PostConstruct
    public void start() {
        // 队列深度注册成 gauge：积压涨起来 = 写库跟不上，这是"观测要坏"的最早信号
        metrics.bindQueueDepth(queue::size);
        if (!properties.isEnabled() || running) {
            return;
        }
        running = true;
        worker = new Thread(this::loop, "ai-obs-writer");
        worker.setDaemon(true);
        worker.start();
        log.info("ObsWriter#start#观测写入线程已启动 capacity={} batch={} flush={}ms",
                properties.getQueueCapacity(), properties.getBatchSize(), properties.getFlushIntervalMs());
    }

    /**
     * 入队。永不抛异常、永不阻塞——这是整个类最重要的契约。
     */
    public void offer(RunRow run, List<LlmCallRow> llmCalls, List<ToolCallRow> toolCalls) {
        if (!properties.isEnabled() || run == null) {
            return;
        }
        Batch batch = new Batch(run, llmCalls == null ? List.of() : llmCalls,
                toolCalls == null ? List.of() : toolCalls);
        if (!queue.offer(batch)) {
            long total = dropped.incrementAndGet();
            metrics.obsDropped();
            if (total == 1 || total % 100 == 0) {
                log.warn("ObsWriter#offer#队列已满，丢弃观测数据累计={}", total);
            }
        }
    }

    /**
     * 收尾：把 scope 里累积的一整批（run + llm_call + tool_call）入队。
     *
     * <p>在入口的 finally 里调用，<b>不抛异常</b>：观测收尾失败不能变成用户看到 500。</p>
     *
     * <p>trace 的根 span 与任务级指标在这里收口（不在写库线程）：
     * 它们是"请求的真实耗时"，放到异步线程再结束会把队列等待也算进去。</p>
     */
    public void finish(RunScope scope, String status, String errorCode) {
        if (scope == null || scope.noop() || !properties.isEnabled()) {
            return;
        }
        try {
            RunRow row = scope.finish(status, errorCode);
            offer(row, scope.getLlmCalls(), scope.getToolCalls());
            if (row != null) {
                long duration = durationOf(row);
                metrics.runFinished(row, duration);
                // 原文只在内存里（RunRow 的 input_text / output_text 都不进 insert），
                // 在这里写给 Langfuse 的 Input/Output 主字段，不落业务库。
                ObsHub.tracing().endRun(scope.getSpan(), row.getStatus(), errorCode,
                        nz(row.getStepCount()), nz(row.getInputTokens()), nz(row.getOutputTokens()),
                        row.getCostCny() == null ? 0D : row.getCostCny().doubleValue(), duration,
                        row.getInputText(), row.getOutputText());
            }
        } catch (RuntimeException e) {
            log.warn("ObsWriter#finish#收尾失败（不影响业务） err={}", e.toString());
        }
    }

    // ---------------- 内部 ----------------

    private void loop() {
        List<Batch> buffer = new ArrayList<>(properties.getBatchSize());
        while (running) {
            try {
                Batch first = queue.poll(properties.getFlushIntervalMs(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                buffer.clear();
                buffer.add(first);
                queue.drainTo(buffer, Math.max(properties.getBatchSize() - 1, 0));
                flush(buffer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // 单批失败不杀线程，否则一次网络抖动会永久停止观测
                log.warn("ObsWriter#loop#刷盘异常 size={} err={}", buffer.size(), e.toString());
            }
        }
    }

    private void flush(List<Batch> batchList) {
        List<RunRow> runs = new ArrayList<>(batchList.size());
        List<LlmCallRow> llmCalls = new ArrayList<>();
        List<ToolCallRow> toolCalls = new ArrayList<>();
        List<QualityResult> results = new ArrayList<>(batchList.size());
        for (Batch b : batchList) {
            // 先判定再入库：task_success / quality_label 与任务行同一条 insert，
            // 不给自己留"任务写了、结论没写"的中间态
            results.add(qualityEvaluator.evaluate(b.run, b.toolCalls));
            runs.add(b.run);
            if (properties.isPersistLlmCall()) {
                llmCalls.addAll(b.llmCalls);
            }
            if (properties.isPersistToolCall()) {
                toolCalls.addAll(b.toolCalls);
            }
        }
        insert("ai_run", () -> mapper.insertRuns(runs));
        if (!llmCalls.isEmpty()) {
            insert("ai_llm_call", () -> mapper.insertLlmCalls(llmCalls));
        }
        if (!toolCalls.isEmpty()) {
            insert("ai_tool_call", () -> mapper.insertToolCalls(toolCalls));
        }
        // 评分依赖 run_id，必须在 ai_run 落库之后再写
        for (int i = 0; i < runs.size(); i++) {
            qualityEvaluator.persist(runs.get(i), results.get(i), batchList.get(i).toolCalls);
        }
        written.addAndGet(runs.size());
    }

    private void insert(String table, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            writeFailed.incrementAndGet();
            metrics.obsWriteFailed(table);
            log.warn("ObsWriter#insert#写库失败 table={} err={}", table, e.toString());
        }
    }

    /** 关停：把队列里剩的刷完，最多等 3 秒 */
    @PreDestroy
    @Override
    public void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        List<Batch> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            flush(rest);
        }
        log.info("ObsWriter#close#已停止 written={} dropped={} failed={} rest={}",
                written.get(), dropped.get(), writeFailed.get(), rest.size());
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static int durationOf(RunRow row) {
        return row.getDurationMs() == null ? 0 : row.getDurationMs();
    }

    private static final class Batch {
        private final RunRow run;
        private final List<LlmCallRow> llmCalls;
        private final List<ToolCallRow> toolCalls;

        private Batch(RunRow run, List<LlmCallRow> llmCalls, List<ToolCallRow> toolCalls) {
            this.run = run;
            this.llmCalls = llmCalls;
            this.toolCalls = toolCalls;
        }
    }
}
