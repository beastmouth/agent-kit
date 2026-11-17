package com.beastmouth.agentkit.eval.mapper;

import com.beastmouth.agentkit.eval.EvalScoreRow;
import com.beastmouth.agentkit.eval.FeedbackRow;
import com.beastmouth.agentkit.eval.GoldenCaseRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 质量闭环的读写（评分、反馈、黄金集）。
 *
 * <p>与 {@code RunMapper} 分开是有意的：那边只做"批量 append 日志"，这边有查询与
 * upsert，两者的失败后果也不同——评分写失败只丢一个分数，run 写失败会丢整条任务。
 * 混在一个接口里，出问题时就分不清是哪一类。</p>
 */
@Mapper
public interface EvalMapper {

    @Insert("""
            <script>
            INSERT INTO ai_eval_score
              (id, run_id, trace_id, eval_set, metric, score, label, judge_model, rubric_version, detail_json, batch_id, created_at)
            VALUES
            <foreach collection="list" item="r" separator=",">
              (#{r.id}, #{r.runId}, #{r.traceId}, #{r.evalSet}, #{r.metric}, #{r.score}, #{r.label},
               #{r.judgeModel}, #{r.rubricVersion}, #{r.detailJson}, #{r.batchId}, #{r.createdAt})
            </foreach>
            </script>
            """)
    int insertScores(@Param("list") List<EvalScoreRow> list);

    /** 同一任务同一人只保留一条（uk_run_subject），重复提交按最后一次算 */
    @Insert("""
            INSERT INTO ai_feedback (id, run_id, subject_id, rating, tags, comment, created_at)
            VALUES (#{id}, #{runId}, #{subjectId}, #{rating}, #{tags}, #{comment}, #{createdAt})
            ON DUPLICATE KEY UPDATE rating = VALUES(rating), tags = VALUES(tags),
                                    comment = VALUES(comment), created_at = VALUES(created_at)
            """)
    int upsertFeedback(FeedbackRow row);

    /** 前端点踩时只知道"刚才那句话"，不知道 runId，所以要能按会话倒查最近一条 */
    @Select("""
            SELECT id FROM ai_run
            WHERE session_id = #{sessionId}
            ORDER BY created_at DESC
            LIMIT 1
            """)
    Long findLatestRunIdBySession(@Param("sessionId") Long sessionId);

    @Select("""
            SELECT id, name, layer, input, expected_tool AS expectedTool,
                   expected_rows AS expectedRows, expect_clarify AS expectClarify, enabled
            FROM ai_golden_case
            WHERE enabled = 1
            ORDER BY id
            """)
    List<GoldenCaseRow> selectGoldenCases();

    /**
     * 上一次黄金集批次的平均成功率，作为"不降"的基线。
     *
     * <p>刻意不看更早的历史：两周前的一次事故不该长期压着基线。
     * 内层包一层派生表，是因为 MySQL 不允许在子查询里直接读同一张表。</p>
     *
     * <p><b>必须按 {@code rubric_version} 切开</b>：v2 → v3 把「工具报错也算通过」堵掉、
     * 把越权层从「必须登记反馈」改成「没有得逞」，同一个模型的分数会整段位移。
     * 不切版本的话，换 rubric 的下一批要么被旧基线压死（收紧时），要么白捡一次宽松
     * （放松时），两种都会让门禁读出与代码无关的结论。没有同版本基线时返回 null，
     * {@code gate} 会把本批当基线存档——这正是换版本时的正确行为。</p>
     */
    @Select("""
            SELECT ROUND(AVG(s.score), 4) FROM ai_eval_score s
            WHERE s.eval_set = 'GOLDEN' AND s.metric = 'task_success'
              AND s.rubric_version = #{rubricVersion}
              AND s.batch_id = (
                SELECT b.batch_id FROM (
                  SELECT batch_id FROM ai_eval_score
                  WHERE eval_set = 'GOLDEN' AND metric = 'task_success'
                    AND rubric_version = #{rubricVersion}
                    AND batch_id IS NOT NULL AND batch_id <> #{batchId}
                  ORDER BY created_at DESC LIMIT 1
                ) b
              )
            """)
    BigDecimal selectPreviousGoldenAverage(@Param("batchId") String batchId,
                                           @Param("rubricVersion") String rubricVersion);

    @Select("""
            SELECT COUNT(*) FROM ai_golden_case WHERE enabled = 1
            """)
    int countGoldenCases();

}
