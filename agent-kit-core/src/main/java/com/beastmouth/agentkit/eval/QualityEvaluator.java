package com.beastmouth.agentkit.eval;

import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.obs.AiMetrics;
import com.beastmouth.agentkit.obs.IdGen;
import com.beastmouth.agentkit.obs.ObsHub;
import com.beastmouth.agentkit.obs.RunRow;
import com.beastmouth.agentkit.obs.ToolCallRow;
import com.beastmouth.agentkit.eval.mapper.EvalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 质量评估的执行者：算规则 → 回填 {@code ai_run} → 落 {@code ai_eval_score}。
 *
 * <p>整条链路跑在 {@code ObsWriter} 的写库线程里，<b>不占请求线程</b>：
 * 判定是纯字符串比对，落库是批量 insert，加起来比一次 Redis 往返还便宜，
 * 但放到 SSE 线程上就是白白给用户加延迟。</p>
 *
 * <p>契约与埋点一致：任何异常只降级为日志，绝不影响已入库的 ai_run。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityEvaluator {

    private final EvalProperties properties;
    private final AiProperties aiProperties;
    private final EvalMapper mapper;
    /** 懒解析：judge 未配置时这条链路连 Bean 都不该被创建出来 */
    private final ObjectProvider<JudgeService> judgeProvider;

    /**
     * 判定并回填任务行（不改库）。
     *
     * <p>回填的三个字段（{@code task_success / quality_label / output_preview}）与 ai_run
     * 是同一条 insert 写进去的，所以<b>不存在"任务行有了、判定没跟上"的中间态</b>。</p>
     */
    public QualityResult evaluate(RunRow row, List<ToolCallRow> tools) {
        if (row == null || !properties.isEnabled()) {
            return QualityResult.of(QualityLabel.OK, "DISABLED", null);
        }
        QualityResult result;
        try {
            result = QualityRules.evaluate(row, tools, aiProperties.getMaxSteps());
        } catch (RuntimeException e) {
            log.debug("QualityEvaluator#evaluate#规则判定失败 err={}", e.toString());
            result = QualityResult.of(QualityLabel.OK, "ERROR", null);
        }
        row.setTaskSuccess(result.success() ? 1 : 0);
        row.setQualityLabel(result.label().name());
        metrics().evalScore("task_success", result.label().name());
        if (result.label() == QualityLabel.HALLUCINATION) {
            metrics().hallucinatedSuccess(row.getAgentName());
            // 红线单独打一条 error 日志：告警规则之外，日志也是最后一道兜底
            log.error("QualityEvaluator#hallucination#幻觉成功 run={} trace={} agent={} evidence={}",
                    row.getId(), row.getTraceId(), row.getAgentName(), result.evidence());
        }
        return result;
    }

    /** 落评分明细。失败只记日志——评分没了可以重跑，任务行丢了就真没了 */
    public void persist(RunRow row, QualityResult result, List<ToolCallRow> tools) {
        if (row == null || result == null || !properties.isEnabled() || row.getId() == null) {
            return;
        }
        try {
            List<EvalScoreRow> rows = new ArrayList<>(2);
            rows.add(score(row, "task_success", result.success() ? BigDecimal.ONE : BigDecimal.ZERO,
                    result.label().name(), result));
            if (result.label() == QualityLabel.HALLUCINATION) {
                rows.add(score(row, "faithfulness", BigDecimal.ZERO, "HALLUCINATION", result));
            } else if (result.label() == QualityLabel.TOOL_FAIL) {
                rows.add(score(row, "tool_accuracy", BigDecimal.ZERO, "TOOL_FAIL", result));
            }
            mapper.insertScores(rows);
            // 送 judge：100% 失败样本 + 按比例的通过样本。judge 未配置时这里只计数不报错
            if (shouldJudge(result)) {
                JudgeService judge = judgeProvider.getIfAvailable();
                if (judge == null || !judge.available()) {
                    metrics().judgeSkipped("no_model");
                } else {
                    judge.review(row, tools);
                }
            }
        } catch (RuntimeException e) {
            log.warn("QualityEvaluator#persist#评分落库失败 run={} err={}", row.getId(), e.toString());
            metrics().obsWriteFailed("ai_eval_score");
        }
    }

    /**
     * 是否送 LLM 评审。
     *
     * <p>抽样策略：<b>100% 失败样本 + 按比例的通过样本</b>。反过来（只抽通过样本）就永远
     * 看不到失败模式，而这恰恰是最需要看的东西。</p>
     */
    public boolean shouldJudge(QualityResult result) {
        if (!properties.isEnabled() || !properties.isJudgeEnabled()) {
            return false;
        }
        if (properties.getJudgeModel() == null || properties.getJudgeModel().isBlank()) {
            return false;
        }
        if (!result.success()) {
            return true;
        }
        double rate = Math.max(0D, Math.min(1D, properties.getSampleRate()));
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    private EvalScoreRow score(RunRow row, String metric, BigDecimal value, String label, QualityResult result) {
        EvalScoreRow score = new EvalScoreRow();
        score.setId(IdGen.next());
        score.setRunId(row.getId());
        score.setTraceId(row.getTraceId());
        score.setEvalSet("ONLINE");
        score.setMetric(metric);
        score.setScore(value);
        score.setLabel(label);
        score.setRubricVersion(properties.getRubricVersion());
        score.setDetailJson(detail(result));
        score.setCreatedAt(LocalDateTime.now());
        return score;
    }

    /** 详情只留规则 id + 短证据，别把回复原文塞进 JSON 列（那是另一个合规问题） */
    private String detail(QualityResult result) {
        if (result.rule() == null || "NONE".equals(result.rule())) {
            return null;
        }
        String evidence = result.evidence() == null ? "" : result.evidence().replace("\"", "'");
        if (evidence.length() > 180) {
            evidence = evidence.substring(0, 180) + "…";
        }
        return "{\"rule\":\"" + result.rule() + "\",\"evidence\":\"" + evidence + "\"}";
    }

    private AiMetrics metrics() {
        return ObsHub.metrics();
    }
}
