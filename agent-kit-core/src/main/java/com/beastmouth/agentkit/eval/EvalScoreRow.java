package com.beastmouth.agentkit.eval;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 对应表 {@code ai_eval_score}：一条评分记录 */
@Data
public class EvalScoreRow {

    private Long id;
    private Long runId;
    private String traceId;
    /** ONLINE / GOLDEN / CI */
    private String evalSet;
    /** task_success / faithfulness / tool_accuracy / completeness */
    private String metric;
    private BigDecimal score;
    private String label;
    private String judgeModel;
    private String rubricVersion;
    private String detailJson;
    /** 黄金集批次号，用于"这一次跑 vs 上一次跑"的对比 */
    private String batchId;
    private LocalDateTime createdAt;
}
