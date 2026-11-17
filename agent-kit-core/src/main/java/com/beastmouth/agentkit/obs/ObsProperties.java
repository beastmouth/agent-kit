package com.beastmouth.agentkit.obs;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 观测相关配置，全部挂在 {@code agent-kit.obs} 下。
 *
 * <p>默认值刻意保守：观测是旁路，<b>默认不放大文本、不阻塞、不参与业务事务</b>。
 * 任何一个开关出问题，都应该表现为"报表不准"，而不是"对话不可用"。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent-kit.obs")
public class ObsProperties {

    /** 总开关。关掉后 RunScope 退化为 NOOP，队列不启动，零开销 */
    private boolean enabled = true;

    /** 队列容量。满了直接丢并计数——宁可丢观测，不可拖慢 SSE */
    private int queueCapacity = 10_000;

    /** 攒批条数，达到即刷 */
    private int batchSize = 50;

    /** 定时刷盘间隔（毫秒） */
    private long flushIntervalMs = 200;

    /** prompt 预览保留字数，落 ai_run.input_preview */
    private int previewChars = 200;

    /** 工具入参落库前截断长度，落 ai_tool_call.args_json */
    private int argsChars = 1024;

    /**
     * 是否把原文写进 Langfuse 的 Input / Output 主字段（{@code langfuse.observation.input|output}）。
     * 关掉后 Input/Output 恢复为空，其余 trace 属性与 MySQL 预览不受影响——
     * 原文可能含用户隐私与业务敏感数据，这个开关是数据留存策略的唯一闸门。
     */
    private boolean traceIoEnabled = true;

    /**
     * 单条 Input/Output 的字符上限。只作用于上报给 Langfuse 的文本，不改业务库预览口径。
     * 超长会截断并打 {@code truncated:true}；设 0 表示不截断（大 prompt 会显著放大 OTLP 包）。
     */
    private int traceIoChars = 8000;

    /** 明细保留天数，供清理任务读取（90 天是决议值） */
    private int detailRetentionDays = 90;

    /** 价目表本地缓存刷新间隔（秒），改价后最多等这么久生效 */
    private long priceRefreshSeconds = 300;

    /** 是否把每条 llm_call 也写库（调试可关，指标不受影响） */
    private boolean persistLlmCall = true;

    /** 是否把每条 tool_call 也写库 */
    private boolean persistToolCall = true;
}
