package com.beastmouth.agentkit.obs;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 对应表 {@code ai_llm_call}：一次模型调用一行 */
@Data
public class LlmCallRow {

    private Long id;
    private Long runId;
    private String traceId;
    private Integer stepSeq;
    private String provider;
    private String model;
    private String purpose;
    private Integer stream;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer cachedTokens;
    private Integer reasoningTokens;
    private Integer usageEstimated;
    private BigDecimal costCny;
    private Integer ttftMs;
    private Integer durationMs;
    private Integer requestChars;
    private Integer responseChars;
    private String finishReason;
    private String status;
    private String errorCode;
    private LocalDateTime createdAt;
}
