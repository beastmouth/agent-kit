package com.beastmouth.agentkit.obs;

import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

/**
 * 一次工具执行的记录收口（{@code ai_tool_call} + 指标 + span）。
 *
 * <h3>为什么是静态工具类而不是 Bean</h3>
 * <p>它只依赖两个配置数字（{@code argsChars}）和三处静态出口（{@link ObsHub}、{@link RunScope}），
 * 没有任何可替换的协作者。做成 Bean 的代价是：{@code SubAgentRunner} 与
 * 各工具适配器的构造器各加一个参数，连带一批测试跟着改——而它们的行为
 * 一秒都不会变。项目里的 {@link RunScope} 本身就是静态工具类，这里保持同一风格。</p>
 *
 * <h3>为什么要收口</h3>
 * <p>「工具返回 0 条却给出结论」是自研 Agent 最典型的病，抓它的唯一凭据是
 * {@code result_rows}。「工具名幻觉」的唯一凭据是 {@code error_code=NOT_FOUND}。
 * 这些字段散在两个引擎里各写一遍，迟早会出现一边记 rows、一边忘记录的情况，
 * 那天空结果率就永远算不准。</p>
 */
@Slf4j
public final class ToolCallRecorder {

    private ToolCallRecorder() {
    }

    /**
     * 记一笔工具调用。
     *
     * @param scope       当前任务上下文，可为 null / NOOP（此时只出指标与 span）
     * @param toolName    工具名（模型给的名字，可能是幻觉出来的）
     * @param argsJson    原始入参 JSON，本方法内部按 {@code argsChars} 截断
     * @param success     是否成功
     * @param rows        结果条数；null 表示「该工具不产条数」，0 表示「查了但没数据」
     * @param durationMs  执行耗时
     * @param errorCode   NOT_FOUND / 异常类名等，成功时为 null
     * @param outcomeData 工具返回的数据原文，用于识别 {@code needClarify}（可为 null）
     * @param span        工具执行前开的 span（可为 null / invalid）
     * @param argsChars   入参落库截断长度，来自 {@code agent-kit.obs.args-chars}
     */
    public static void record(RunScope scope, String toolName, String argsJson, boolean success,
                              Integer rows, long durationMs, String errorCode, String outcomeData,
                              Span span, int argsChars) {
        try {
            // 指标与 span 不依赖 scope：scope 是 NOOP（定时任务/单测）时也要能出数
            ObsHub.metrics().toolCall(toolName, success, rows, durationMs);
            // 入参与返回数据原文只给 Langfuse；落库仍按 argsChars 截断，outcomeData 不进明细表
            ObsHub.tracing().endTool(span, success, rows, durationMs, argsJson, outcomeData);
            if (scope == null || scope.noop()) {
                return;
            }
            if (clarifyRequested(success, outcomeData)) {
                // 工具主动要求澄清（分类不存在/匹配到多个）：正常行为，不是失败。
                // 但不留痕就看板上分不清「问了一句」和「什么也没答」，质量标签会全落到 OK
                scope.flagClarify();
            }
            // 空结果标志由 RunScope#recordToolCall 统一置位（rows==0），这里不重复判断
            ToolCallRow row = new ToolCallRow();
            row.setId(IdGen.next());
            row.setRunId(scope.getId());
            row.setTraceId(scope.getTraceId());
            row.setStepSeq(scope.nextSeq());
            row.setToolName(abbreviate(toolName, 64));
            row.setArgsJson(abbreviate(argsJson, argsChars));
            row.setStatus(success ? "OK" : "FAIL");
            row.setErrorCode(errorCode);
            row.setResultRows(rows);
            row.setDurationMs((int) Math.min(Math.max(durationMs, 0), Integer.MAX_VALUE));
            row.setCreatedAt(LocalDateTime.now());
            scope.recordToolCall(row);
        } catch (RuntimeException e) {
            // 埋点绝不影响回答，连 warn 都降级，避免观测异常刷满日志
            log.debug("ToolCallRecorder#record#记录失败 tool={} err={}", toolName, e.toString());
        }
    }

    /**
     * 工具是否要求模型先向用户澄清。
     *
     * <p>约定写在工具返回值的数据里（{@code {"needClarify":true}}），因为它同时带着候选分类。
     * 解析失败按「没要求」处理——<b>宁可少打一个标，也不要让埋点影响工具本身</b>。</p>
     */
    private static boolean clarifyRequested(boolean success, String outcomeData) {
        if (!success || outcomeData == null || outcomeData.isBlank()) {
            return false;
        }
        try {
            int idx = outcomeData.indexOf("\"needClarify\"");
            if (idx < 0) {
                return false;
            }
            String tail = outcomeData.substring(idx);
            int colon = tail.indexOf(':');
            if (colon < 0) {
                return false;
            }
            return tail.substring(colon + 1).stripLeading().startsWith("true");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 落库前按列宽截断。
     *
     * <p>不是「日志好看」而是「不能撞 VARCHAR 上限」：一条超长入参能让整批 insert 失败，
     * 连带丢掉同批其他任务的观测数据。</p>
     */
    static String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        String flat = value.replaceAll("\\s+", " ");
        if (max <= 0) {
            return flat;
        }
        return flat.length() <= max ? flat : flat.substring(0, Math.max(max - 1, 0)) + "…";
    }
}
