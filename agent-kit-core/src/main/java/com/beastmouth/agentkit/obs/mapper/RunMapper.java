package com.beastmouth.agentkit.obs.mapper;

import com.beastmouth.agentkit.obs.LlmCallRow;
import com.beastmouth.agentkit.obs.ModelPrice;
import com.beastmouth.agentkit.obs.RunRow;
import com.beastmouth.agentkit.obs.ToolCallRow;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 观测表批量写入。
 *
 * <p>刻意不用 MyBatis-Plus 的 {@code saveBatch}：那套会带实体注解和字段填充，
 * 观测表是纯日志、无逻辑删除、无乐观锁，用原生批量 insert 更轻也更可控。</p>
 *
 * <p>每批 50 条左右，单条 SQL 的 values 行数可控，避免超过 {@code max_allowed_packet}。</p>
 */
@Mapper
public interface RunMapper {

    @Insert("""
            <script>
            INSERT INTO ai_run
              (id, trace_id, subject_id, labels, session_id, channel, agent_name, entry_type,
               input_hash, input_preview, output_preview, status, error_code, step_count, llm_call_count, tool_call_count,
               input_tokens, output_tokens, cached_tokens, reasoning_tokens, cost_cny,
               ttft_ms, duration_ms, fallback_used, parse_fail, fallback_reply, need_clarify, empty_result,
               task_success, quality_label, created_at)
            VALUES
            <foreach collection="list" item="r" separator=",">
              (#{r.id}, #{r.traceId}, #{r.subjectId}, #{r.labels}, #{r.sessionId}, #{r.channel}, #{r.agentName}, #{r.entryType},
               #{r.inputHash}, #{r.inputPreview}, #{r.outputPreview}, #{r.status}, #{r.errorCode}, #{r.stepCount}, #{r.llmCallCount}, #{r.toolCallCount},
               #{r.inputTokens}, #{r.outputTokens}, #{r.cachedTokens}, #{r.reasoningTokens}, #{r.costCny},
               #{r.ttftMs}, #{r.durationMs}, #{r.fallbackUsed}, #{r.parseFail}, #{r.fallbackReply}, #{r.needClarify}, #{r.emptyResult},
               #{r.taskSuccess}, #{r.qualityLabel}, #{r.createdAt})
            </foreach>
            </script>
            """)
    int insertRuns(@Param("list") List<RunRow> list);

    @Insert("""
            <script>
            INSERT INTO ai_llm_call
              (id, run_id, trace_id, step_seq, provider, model, purpose, stream,
               input_tokens, output_tokens, cached_tokens, reasoning_tokens, usage_estimated, cost_cny,
               ttft_ms, duration_ms, request_chars, response_chars, finish_reason, status, error_code, created_at)
            VALUES
            <foreach collection="list" item="r" separator=",">
              (#{r.id}, #{r.runId}, #{r.traceId}, #{r.stepSeq}, #{r.provider}, #{r.model}, #{r.purpose}, #{r.stream},
               #{r.inputTokens}, #{r.outputTokens}, #{r.cachedTokens}, #{r.reasoningTokens}, #{r.usageEstimated}, #{r.costCny},
               #{r.ttftMs}, #{r.durationMs}, #{r.requestChars}, #{r.responseChars}, #{r.finishReason}, #{r.status}, #{r.errorCode}, #{r.createdAt})
            </foreach>
            </script>
            """)
    int insertLlmCalls(@Param("list") List<LlmCallRow> list);

    @Insert("""
            <script>
            INSERT INTO ai_tool_call
              (id, run_id, trace_id, step_seq, tool_name, args_json, status, error_code, result_rows, duration_ms, created_at)
            VALUES
            <foreach collection="list" item="r" separator=",">
              (#{r.id}, #{r.runId}, #{r.traceId}, #{r.stepSeq}, #{r.toolName}, #{r.argsJson}, #{r.status},
               #{r.errorCode}, #{r.resultRows}, #{r.durationMs}, #{r.createdAt})
            </foreach>
            </script>
            """)
    int insertToolCalls(@Param("list") List<ToolCallRow> list);

    /**
     * 近期「模型调用了不存在的工具」的记录（errorCode=NOT_FOUND）。
     *
     * <p>这是能力缺口最硬的信号：模型幻觉出一个工具名，等于用户要的某类能力
     * 在工具清单里没有对应物。只读查询，观测表是纯日志（无逻辑删除列），
     * 显式列出列名避免表结构变化时悄悄错位。</p>
     */
    @Select("""
            SELECT id, run_id, trace_id, step_seq, tool_name, args_json, status, error_code,
                   result_rows, duration_ms, created_at
            FROM ai_tool_call
            WHERE error_code = 'NOT_FOUND' AND created_at >= #{since}
            ORDER BY created_at DESC
            LIMIT 50
            """)
    List<ToolCallRow> selectNotFoundTools(@Param("since") LocalDateTime since);

    /** 统计某工具近期的调用量（自检里用 account_update 的次数估算「纠错活跃度」） */
    @Select("""
            SELECT COUNT(*)
            FROM ai_tool_call
            WHERE tool_name = #{toolName} AND created_at >= #{since}
            """)
    int countToolCalls(@Param("toolName") String toolName, @Param("since") LocalDateTime since);
}
