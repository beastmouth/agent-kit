package com.beastmouth.agentkit.obs;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 对应表 {@code ai_run}：一次任务一行 */
@Data
public class RunRow {

    private Long id;
    private String traceId;
    /** 调用方标识：脚手架不解析，只原样落库（见 RunContext.Subject） */
    private String subjectId;
    /** 调用方自定义标签的 JSON 串，供业务侧分组统计 */
    private String labels;
    private Long sessionId;
    private String channel;
    private String agentName;
    private String entryType;
    private String inputHash;
    private String inputPreview;
    /** 用户输入原文：只用于写 Langfuse 的 Input 主字段，<b>不落库</b>（不进 insert 语句） */
    private String inputText;
    /** 回复前 300 字预览，落 ai_run.output_preview（V4 新增） */
    private String outputPreview;
    /** 回复原文：只用于质量规则判定，<b>不落库</b>（不进 insert 语句） */
    private String outputText;
    private String status;
    private String errorCode;
    private Integer stepCount;
    private Integer llmCallCount;
    private Integer toolCallCount;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer cachedTokens;
    private Integer reasoningTokens;
    private BigDecimal costCny;
    private Integer ttftMs;
    private Integer durationMs;
    private Integer fallbackUsed;
    private Integer parseFail;
    private Integer fallbackReply;
    private Integer needClarify;
    private Integer emptyResult;
    /** 1=成功 0=失败 NULL=未判定，由 P3 的规则引擎写入（V4 新增） */
    private Integer taskSuccess;
    /** 质量标签：OK/HALLUCINATION/EMPTY_ASSERT/PARSE_FAIL/TRUNCATED/FALLBACK/OVERSTEP/TOOL_FAIL */
    private String qualityLabel;
    private LocalDateTime createdAt;
}
