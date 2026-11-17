package com.beastmouth.agentkit.eval;

import lombok.Data;

import java.time.LocalDateTime;

/** 对应表 {@code ai_feedback}：用户显式点踩（同一任务同一人只记一次） */
@Data
public class FeedbackRow {

    private Long id;
    private Long runId;
    /** 调用方标识（原样透传，脚手架不解析） */
    private String subjectId;
    /** UP / DOWN / NEUTRAL */
    private String rating;
    /** 答非所问/数据不对/太慢/编造/太啰嗦 */
    private String tags;
    private String comment;
    private LocalDateTime createdAt;
}
