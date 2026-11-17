package com.beastmouth.agentkit.obs;

import io.opentelemetry.api.trace.Span;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次模型调用的记录收口（{@code ai_llm_call} + 指标 + span）。
 *
 * <h3>为什么单独抽出来</h3>
 * <p>项目现在有两条执行路径：自研引擎（{@code ObservedModelCall} 在调用点上包一层）和
 * AgentScope 引擎（框架内部发模型请求，只能靠 Hook 拦截）。两者拿到的原始数据形状不同
 * （{@code ModelReply} vs {@code ChatUsage}），但<b>落库口径必须完全一致</b>——
 * 否则同一个看板上两段时期的「命中率」「每单成本」不可比，评测也没有基线。
 * 所以口径只写在这一处，两边都往这里喂 {@link Sample}。</p>
 *
 * <h3>估算规则（与 P0 定下的一致）</h3>
 * <ul>
 *   <li>网关没回 usage 时按字符估算，并打 {@code usage_estimated=1}，报表上单独标灰；</li>
 *   <li>估算态一律把命中数记 0：字符估出来的输入配一个编的命中数，比没有更糟；</li>
 *   <li>命中数不得超过输入数（厂商偶尔会给出越界值，落库前夹紧）。</li>
 * </ul>
 *
 * <h3>容错</h3>
 * <p>记录失败只降级为 debug 日志 + {@code OBS_FAIL} 收尾 span，<b>绝不抛给业务</b>。</p>
 */
@Slf4j
@Component
public class LlmCallRecorder {

    private final CostCalculator costCalculator;

    public LlmCallRecorder(CostCalculator costCalculator) {
        this.costCalculator = costCalculator;
    }

    /**
     * 一次模型调用的原始样本。
     *
     * <p>数字口径刻意保持「厂商无关」：两套引擎拿到的原始对象不同
     * （{@code ModelReply} 与框架的 {@code ChatUsage}），这里只留能对上的数字。
     * 网关没给 usage 时把 token 留 0，估算由本类完成——调用方自己算就会有两种误差。
     * 新增的原文文本只是透传给 Langfuse，不参与任何计算、也不落库。</p>
     */
    @Getter
    @Builder
    public static class Sample {
        /** 渠道 id（deepseek / ollama ...），不是模型名 */
        private final String provider;
        private final String model;
        /** ACT / SUMMARIZE / ROUTE，与既有报表口径一致 */
        private final String purpose;
        /** 1 = 流式 */
        private final int stream;
        private final Integer inputTokens;
        private final Integer outputTokens;
        private final Integer cachedTokens;
        private final Integer reasoningTokens;
        /** 首次拿到增量的耗时；非流式为 0 */
        private final long ttftMs;
        private final long durationMs;
        private final int requestChars;
        private final int responseChars;
        /**
         * prompt / completion 原文，只用于写 Langfuse 的 Input/Output 主字段，
         * <b>不进 ai_llm_call</b>——明细表只留字符数与 token，避免业务库堆文本。
         * 两套引擎拿到的对象不同（ChatMessage 列表 / AgentScope Msg），这里统一收敛成文本。
         */
        private final String requestText;
        private final String responseText;
        /** OK / ERROR */
        private final String status;
        private final String errorCode;
        /** 调用开始时从 {@link RunScope#nextSeq()} 取的序号，保证与工具调用同一套时序 */
        private final int stepSeq;
    }

    /**
     * 记一笔。
     *
     * @param scope 当前任务的观测上下文（可为 NOOP：此时只出指标与 span，不落明细）
     * @param sample 原始样本
     * @param span 调用开始时开的 generation span（可为 null / invalid）
     */
    public void record(RunScope scope, Sample sample, Span span) {
        if (sample == null) {
            return;
        }
        try {
            boolean failed = "ERROR".equalsIgnoreCase(sample.getStatus());
            int input = nz(sample.getInputTokens());
            int output = nz(sample.getOutputTokens());
            int responseChars = Math.max(sample.getResponseChars(), 0);

            // 口径与 P0 完全一致：input/output 都是 0 却有正文 → 网关没给 usage，按字符估算
            boolean estimated = !failed && input == 0 && output == 0 && responseChars > 0;
            // 命中数只在网关给了 usage 时才可信；估算态一律 0
            int cached = (failed || estimated)
                    ? 0
                    : Math.min(Math.max(nz(sample.getCachedTokens()), 0), Math.max(input, 0));
            if (estimated) {
                output = Tokens.estimate(responseChars);
                input = Tokens.estimate(sample.getRequestChars());
            }

            BigDecimal cost = failed
                    ? BigDecimal.ZERO
                    : costCalculator.calc(sample.getProvider(), sample.getModel(),
                            input, cached, output, LocalDateTime.now());

            LlmCallRow row = new LlmCallRow();
            row.setId(IdGen.next());
            if (scope != null && !scope.noop()) {
                row.setRunId(scope.getId());
                row.setTraceId(scope.getTraceId());
            }
            row.setStepSeq(sample.getStepSeq());
            row.setProvider(sample.getProvider());
            row.setModel(sample.getModel());
            row.setPurpose(sample.getPurpose());
            row.setStream(sample.getStream());
            row.setInputTokens(input);
            row.setOutputTokens(output);
            row.setCachedTokens(cached);
            row.setReasoningTokens(nz(sample.getReasoningTokens()));
            row.setUsageEstimated(estimated ? 1 : 0);
            row.setCostCny(cost);
            row.setTtftMs(sample.getTtftMs() > 0 ? (int) sample.getTtftMs() : null);
            row.setDurationMs((int) Math.min(Math.max(sample.getDurationMs(), 0), Integer.MAX_VALUE));
            row.setRequestChars(Math.max(sample.getRequestChars(), 0));
            row.setResponseChars(responseChars);
            row.setStatus(failed ? "ERROR" : "OK");
            row.setErrorCode(sample.getErrorCode());
            row.setCreatedAt(LocalDateTime.now());

            if (scope != null) {
                scope.recordLlmCall(row);
            }

            ObsHub.metrics().llmCall(sample.getProvider(), sample.getModel(), row.getStatus(),
                    input, output, cost.doubleValue(), row.getDurationMs(), sample.getTtftMs());
            ObsHub.metrics().llmCachedTokens(sample.getProvider(), cached);
            if (failed) {
                // 一次模型调用失败即使被上层降级吞掉，也应该是「首选不健康」的信号
                ObsHub.metrics().fallback(sample.getProvider());
            }
            ObsHub.tracing().endLlm(span, input, output, cost.doubleValue(), null, row.getErrorCode(),
                    sample.getRequestText(), sample.getResponseText());

            if (estimated) {
                log.debug("LlmCallRecorder#record#网关未返回 usage，按字符估算 provider={} model={} in={} out={}",
                        sample.getProvider(), sample.getModel(), input, output);
            }
        } catch (RuntimeException e) {
            log.debug("LlmCallRecorder#record#记录失败（不影响业务） err={}", e.toString());
            ObsHub.tracing().endLlm(span, 0, 0, 0D, null, "OBS_FAIL");
        }
    }

    /** 让 span 在异常路径上也能正常收尾，不留悬挂的 generation */
    public void closeSpan(Span span, String errorCode) {
        try {
            ObsHub.tracing().endLlm(span, 0, 0, 0D, null, errorCode);
        } catch (RuntimeException e) {
            log.debug("LlmCallRecorder#closeSpan#收尾失败 err={}", e.toString());
        }
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
