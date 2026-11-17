package com.beastmouth.agentkit.obs;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.beastmouth.agentkit.context.RunContext;
import com.beastmouth.agentkit.support.Json;
import io.opentelemetry.api.trace.Span;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次任务的观测上下文：一个用户请求 = 一个 run = 一条 trace。
 *
 * <p>设计原则：<b>埋点永不抛异常，永不阻塞。</b>没有 scope 时拿到的是 {@link #NOOP}，
 * 所有方法都是空实现，业务代码不需要判空，也不需要 try/catch。</p>
 *
 * <p>传递方式沿用项目里 {@code ChatScope} 的做法（TransmittableThreadLocal），
 * 这样 SSE、{@code @Async}、线程池里都能拿到同一个 runId。</p>
 */
public final class RunScope {

    private static final TransmittableThreadLocal<RunScope> CURRENT = new TransmittableThreadLocal<>();

    /**
     * 当前会话 id，由入口（Web handler / IM 回调）绑定。
     *
     * <p>为什么用 ThreadLocal 而不是加参数：会话 id 只有入口知道，而它要传到
     * {@code SubAgentRunner.run} 最深处才被 {@code RunScope.begin} 用上。加参数要改
     * AgentController → MainAgent → SubAgentRunner 三层签名，任何一个漏改都是编译期能发现、
     * 但语义上容易漏的传导；ThreadLocal 走 TTL，线程池与 SSE 子线程都能继承，
     * 与 {@code AuthContext} / {@code ChatScope} 的处理方式一致。</p>
     */
    private static final TransmittableThreadLocal<Long> SESSION = new TransmittableThreadLocal<>();

    /** 空实现：没有入口埋点时的兜底，保证调用方无需判空 */
    private static final RunScope NOOP = new RunScope(0L, "noop", null, null, 0L, "WEB", "unknown", null, null, null,
            Span.getInvalid());

    private final long id;
    private final String traceId;
    /** 调用方标识：来自 {@link RunContext}，脚手架只透传不解析 */
    private final String subjectId;
    /** 调用方标签的 JSON 串，同时用于 trace 属性与落库 */
    private final String labels;
    private final long sessionId;
    private final String channel;
    private final String agentName;
    private final String inputHash;
    private final String inputPreview;
    /**
     * 用户输入原文：只在内存里，跟 {@link #outputText} 一样<b>不落库</b>。
     * 用途是让根 span 能把完整输入写进 Langfuse 的 Input 主字段——
     * 设计里写「原文由 OTel → Langfuse 承担」，但此前只留了 200 字预览，全文两边都丢了。
     */
    private final String inputText;
    private final LocalDateTime startedAt = LocalDateTime.now();
    private final long startNanos = System.nanoTime();

    /** P2 的根 span；noop 时是 {@link Span#getInvalid()}，后续方法全部安全短路 */
    private final Span span;
    private volatile String outputText;

    private final AtomicInteger seq = new AtomicInteger();
    private final List<LlmCallRow> llmCalls = new ArrayList<>();
    private final List<ToolCallRow> toolCalls = new ArrayList<>();

    private volatile long ttftMs = -1;
    private volatile int inputTokens;
    private volatile int outputTokens;
    private volatile int cachedTokens;
    private volatile int reasoningTokens;
    private volatile BigDecimal cost = BigDecimal.ZERO;
    private volatile boolean parseFail;
    private volatile boolean fallbackReply;
    private volatile boolean needClarify;
    private volatile boolean emptyResult;
    private volatile boolean fallbackUsed;
    private volatile String status = "RUNNING";
    private volatile String errorCode;

    private RunScope(long id, String traceId, String subjectId, String labels, long sessionId,
                     String channel, String agentName, String inputHash, String inputPreview,
                     String inputText, Span span) {
        this.id = id;
        this.traceId = traceId;
        this.subjectId = subjectId;
        this.labels = labels;
        this.sessionId = sessionId;
        this.channel = channel;
        this.agentName = agentName;
        this.inputHash = inputHash;
        this.inputPreview = inputPreview;
        this.inputText = inputText;
        this.span = span;
    }

    // ---------------- 生命周期 ----------------

    /**
     * 在入口开启一次任务。调用方标识从 {@link RunContext} 取，调用方不用传。
     *
     * <p>刻意不抛异常：埋点初始化失败时返回 NOOP，业务照常跑。</p>
     */
    public static RunScope begin(String agentName, String channel, String userInput, int previewChars) {
        return begin(currentSubjectId(), currentLabels(), currentSessionId(),
                agentName, channel, userInput, previewChars);
    }

    /** 当前调用方标识；无上下文（CLI/定时任务）返回 null */
    private static String currentSubjectId() {
        try {
            return RunContext.subjectId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 当前调用方标签，序列化成 JSON 串；没有标签返回 null（落库为 NULL，不是 "{}"） */
    private static String currentLabels() {
        try {
            RunContext.Subject subject = RunContext.current();
            if (subject == null || subject.labels().isEmpty()) {
                return null;
            }
            return Json.toJson(subject.labels());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 绑定当前会话 id。入口处调用，必须配 {@link #unbindSession()} 的 finally，
     * 否则线程池复用会把上一个会话的 id 带到下一个请求的 ai_run 上。
     */
    public static void bindSession(Long sessionId) {
        if (sessionId != null && sessionId > 0) {
            SESSION.set(sessionId);
        }
    }

    public static void unbindSession() {
        SESSION.remove();
    }

    private static long currentSessionId() {
        Long value = SESSION.get();
        return value == null ? 0L : value;
    }

    /** 带显式标识的版本，给定时任务/测试用（无上下文时 RunContext 取不到调用方） */
    public static RunScope begin(String subjectId, String labels, long sessionId,
                                 String agentName, String channel, String userInput, int previewChars) {
        try {
            String ch = channel == null ? "WEB" : channel;
            String preview = preview(userInput, previewChars);
            // 先开 span 再取 traceId：MySQL 的 trace_id 必须与 Langfuse 的 traceId 是同一个值，
            // 否则报表点不进瀑布图。span 开不出来（未配 endpoint）时退回自己生成的 id。
            Span span = ObsHub.tracing().startRun(agentName, ch, subjectId, sessionId > 0 ? sessionId : null,
                    agentName, preview);
            String traceId = ObsHub.tracing().traceId(span);
            if (traceId == null || traceId.isBlank()) {
                traceId = IdGen.traceId();
            }
            RunScope scope = new RunScope(IdGen.next(), traceId, subjectId, labels, sessionId,
                    ch, agentName, Hashing.sha256(userInput), preview, userInput, span);
            CURRENT.set(scope);
            return scope;
        } catch (RuntimeException e) {
            return NOOP;
        }
    }

    public static RunScope current() {
        RunScope scope = CURRENT.get();
        return scope == null ? NOOP : scope;
    }

    /** 必须在入口的 finally 里调用，防止线程池复用串号 */
    public static void clear() {
        CURRENT.remove();
    }

    public boolean noop() {
        return this == NOOP;
    }

    // ---------------- 记录 ----------------

    public int nextSeq() {
        return noop() ? 0 : seq.incrementAndGet();
    }

    /** 只记第一次（任务级 TTFT 是首字，不是每步首字） */
    public void markTtft(long ms) {
        if (!noop() && ttftMs < 0) {
            ttftMs = ms;
        }
    }

    /**
     * 从 run 开始到现在的毫秒数。
     *
     * <p>用途：给「用户可感知的首字」打点。本项目的模型调用是非流式的（一次 chat 拿回整段），
     * 所以 {@code ObservedModelCall#stream} 里的 TTFT 永远不会触发 —— 真正决定用户等多久的
     * 是「第一个 delta 推给前端」的时刻，那只能在 Controller 的 sink 里测，
     * 因此需要这个从 run 起点算起的相对时间。</p>
     */
    public long elapsedMs() {
        return noop() ? 0 : (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public void recordLlmCall(LlmCallRow row) {
        if (noop() || row == null) {
            return;
        }
        synchronized (this) {
            // id 统一在这里兜底：调用方（ObservedModelCall / SubAgentRunner）可能只填业务字段，
            // 漏填主键的后果不是"少一条",而是整批 insert 因 Column 'id' cannot be null 全灭
            if (row.getId() == null) {
                row.setId(IdGen.next());
            }
            llmCalls.add(row);
            // 用 null 兜底而不是直接取值：某些渠道不返回 usage（降级到 Ollama 时就没有），
            // 这里 NPE 会顺着调用点抛到业务线程，违背「埋点永不抛异常」的铁律
            inputTokens += nz(row.getInputTokens());
            outputTokens += nz(row.getOutputTokens());
            cachedTokens += nz(row.getCachedTokens());
            reasoningTokens += nz(row.getReasoningTokens());
            cost = cost.add(row.getCostCny() == null ? BigDecimal.ZERO : row.getCostCny());
        }
    }

    public void recordToolCall(ToolCallRow row) {
        if (noop() || row == null) {
            return;
        }
        synchronized (this) {
            // 同 recordLlmCall：主键兜底，避免一条漏填 id 导致整批 ai_tool_call 写不进去。
            // 这个坑真实发生过——SubAgentRunner 只填了业务字段，线上表现为 ai_tool_call 恒空，
            // 而 ai_run 的 tool_call_count 却是对的，两边对不上才发现。
            if (row.getId() == null) {
                row.setId(IdGen.next());
            }
            toolCalls.add(row);
            if (row.getResultRows() != null && row.getResultRows() == 0) {
                emptyResult = true;
            }
        }
    }

    public void flagParseFail() {
        parseFail = true;
    }

    public void flagFallbackReply() {
        fallbackReply = true;
    }

    public void flagClarify() {
        needClarify = true;
    }

    public void flagFallbackUsed() {
        fallbackUsed = true;
    }

    /**
     * 记下最终回复。
     *
     * <p>P3 的确定性规则要读回复原文：工具失败但回复说"已经记好了"，只有比对文本才抓得到。
     * 这个字段<b>不直接落库</b>（原文可能很长），落的是前 300 字预览。</p>
     */
    public void setOutput(String text) {
        if (!noop()) {
            this.outputText = text;
        }
    }

    public String getOutputText() {
        return outputText;
    }

    public Span getSpan() {
        return span;
    }

    public long getStartNanos() {
        return startNanos;
    }

    /** 结束任务，产出待落库的行；调用方负责交给 writer */
    public RunRow finish(String finalStatus, String finalErrorCode) {
        if (noop()) {
            return null;
        }
        this.status = finalStatus == null ? "SUCCESS" : finalStatus;
        this.errorCode = finalErrorCode;
        return toRow();
    }

    public String getTraceId() {
        return traceId;
    }

    public long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    // ---------------- 组装 ----------------

    private RunRow toRow() {
        RunRow row = new RunRow();
        row.setId(id);
        row.setTraceId(traceId);
        row.setSubjectId(subjectId);
        row.setLabels(labels);
        row.setSessionId(sessionId == 0L ? null : sessionId);
        row.setChannel(channel);
        row.setAgentName(agentName);
        row.setEntryType("CHAT");
        row.setInputHash(inputHash);
        row.setInputPreview(inputPreview);
        row.setInputText(inputText);
        row.setOutputPreview(preview(outputText, 300));
        row.setOutputText(outputText);
        row.setStatus(status);
        row.setErrorCode(errorCode);
        row.setStepCount(llmCalls.size());
        row.setLlmCallCount(llmCalls.size());
        row.setToolCallCount(toolCalls.size());
        row.setInputTokens(inputTokens);
        row.setOutputTokens(outputTokens);
        row.setCachedTokens(cachedTokens);
        row.setReasoningTokens(reasoningTokens);
        row.setCostCny(cost);
        row.setTtftMs(ttftMs < 0 ? 0 : (int) ttftMs);
        row.setDurationMs((int) ((System.nanoTime() - startNanos) / 1_000_000));
        row.setFallbackUsed(fallbackUsed ? 1 : 0);
        row.setParseFail(parseFail ? 1 : 0);
        row.setFallbackReply(fallbackReply ? 1 : 0);
        row.setNeedClarify(needClarify ? 1 : 0);
        row.setEmptyResult(emptyResult ? 1 : 0);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    public List<LlmCallRow> getLlmCalls() {
        return llmCalls;
    }

    public List<ToolCallRow> getToolCalls() {
        return toolCalls;
    }

    /** null 当 0：观测字段缺值不该让记录逻辑崩 */
    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String preview(String text, int chars) {        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= chars ? flat : flat.substring(0, chars) + "…";
    }
}
