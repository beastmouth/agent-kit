package com.beastmouth.agentkit.obs;

import lombok.Data;

import java.time.LocalDateTime;

/** 对应表 {@code ai_tool_call}：一次工具执行一行 */
@Data
public class ToolCallRow {

    private Long id;
    private Long runId;
    private String traceId;
    private Integer stepSeq;
    private String toolName;
    private String argsJson;
    private String status;
    private String errorCode;
    private Integer resultRows;
    private Integer durationMs;
    private LocalDateTime createdAt;
}
