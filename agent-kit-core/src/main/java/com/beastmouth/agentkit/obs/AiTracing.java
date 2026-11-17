package com.beastmouth.agentkit.obs;

import com.beastmouth.agentkit.support.Json;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 链路追踪收口（P2）：agent.run → llm.chat / tool.xxx 三级 span。
 *
 * <h3>为什么直接用 OTel API，而不是 Micrometer Tracing 的 Tracer</h3>
 * <p>Micrometer 的 {@code Tracer} 是抽象层，好处是不绑后端；但我们<b>没有</b>"换后端"的需求
 * （决议已定自托管 Langfuse），而抽象层会藏掉两个我们要用的东西：显式指定 parent、
 * 直接读 traceId 回写 MySQL。{@code ai_run.trace_id} 与 Langfuse 的 traceId 必须是同一个值，
 * 否则"从报表点进去看瀑布图"这一步就断了——所以这里直接拿 OTel 的 Span。</p>
 *
 * <h3>父子关系：显式 parent，不靠 ThreadLocal 的 current</h3>
 * <p>SSE 场景下 run 与子 span 未必在同一个线程上（线程池 + TTL 传递）。如果依赖
 * {@code makeCurrent()}，子 span 很可能变成"孤儿根 span"，Langfuse 里就是一堆散点。
 * 所以 run span 由 {@code RunScope} 持有，子 span 一律 {@code setParent(root.with(runSpan))}。
 * 代价是不支持跨进程传播，收益是<b>在任何线程上都不会挂错爹</b>。</p>
 *
 * <h3>没配 endpoint 时</h3>
 * <p>Boot 不会建 TracerProvider，这里拿到的是 noop，{@link #enabled()} 为 false，
 * 所有方法直接返回 {@link Span#getInvalid()}。整条链路零分配、零请求。</p>
 */
@Slf4j
@Component
public class AiTracing {

    private static final String INSTRUMENTATION = "com.beastmouth.agentkit";

    private static final AttributeKey<String> OBSERVATION_TYPE = AttributeKey.stringKey("langfuse.observation.type");
    /**
     * Langfuse 的 Input / Output 主字段。v4 起 trace 级 input/output 已废弃，
     * trace 详情里的 Input/Output 直接取根 span 的这两个属性；不写就是空面板。
     * 值必须是 JSON 字符串，裸文本要包一层，详见 {@link #ioPayload(String)}。
     */
    private static final AttributeKey<String> OBSERVATION_INPUT = AttributeKey.stringKey("langfuse.observation.input");
    private static final AttributeKey<String> OBSERVATION_OUTPUT = AttributeKey.stringKey("langfuse.observation.output");
    private static final AttributeKey<String> TRACE_NAME = AttributeKey.stringKey("langfuse.trace.name");
    private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("langfuse.user.id");
    private static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("langfuse.session.id");
    private static final AttributeKey<String> TAGS = AttributeKey.stringKey("langfuse.trace.tags");
    private static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
    private static final AttributeKey<String> GEN_AI_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<Long> GEN_AI_INPUT = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> GEN_AI_OUTPUT = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<Double> GEN_AI_COST = AttributeKey.doubleKey("gen_ai.usage.cost");
    private static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("tool.name");
    private static final AttributeKey<Long> TOOL_ROWS = AttributeKey.longKey("tool.result_rows");
    private static final AttributeKey<String> TOOL_STATUS = AttributeKey.stringKey("tool.status");

    private static final AiTracing NOOP = new AiTracing(OpenTelemetry.noop(), new ObsProperties());

    private final Tracer tracer;
    private final boolean enabled;
    private final ObsProperties properties;

    /**
     * 主构造器。必须显式标 {@code @Autowired}：不标的话 Spring 会退回找无参
     * 构造器，找不到就 {@code NoSuchMethodException} 导致启动失败（AiMetrics 踩过一次，
     * 这里同样处理，避免同一个坑埋两遍）。
     */
    @Autowired
    public AiTracing(ObjectProvider<OpenTelemetry> provider, ObsProperties properties) {
        // 容器里没有 OTel bean（未引 bridge / 未配 endpoint）时退回全局 noop，
        // 绝不用 GlobalOpenTelemetry.get()：那会在没初始化 SDK 时创建并锁死一个 noop 单例
        this(provider.getIfAvailable(OpenTelemetry::noop), properties);
    }

    /** 包内可见：单测可以塞 mock 的 OpenTelemetry 与自定义配置，不必启动容器 */
    AiTracing(OpenTelemetry openTelemetry, ObsProperties properties) {
        this.properties = properties == null ? new ObsProperties() : properties;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION);
        // 判断依据不是"有没有 bean"，而是 tracer 的 provider 是否真在采样：
        // noop provider 的 span 恒为 invalid，用它做 enabled 判断最诚实
        Span probe = tracer.spanBuilder("probe").startSpan();
        this.enabled = probe.getSpanContext().isValid();
        probe.end();
    }

    static AiTracing noop() {
        return NOOP;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 采集点用它短路文本序列化：开关关掉时，不该还白拼一遍上万字的 prompt。
     * 后端没接（noop）时同样返回 false。
     */
    public boolean ioEnabled() {
        return enabled && properties.isTraceIoEnabled();
    }

    public String describe() {
        return enabled ? "otel" : "noop";
    }

    // ---------------- 三类 span ----------------

    /**
     * 任务根 span。名字固定为 {@code agent.run}，Langfuse 里按这个查。
     *
     * <p>调用方标识/会话 id 走 {@code langfuse.*} 前缀：Langfuse 只认带前缀的属性，
     * 标准 OTel 属性会被塞进 {@code metadata.attributes} 兜底区，等于白打。
     * 脚手架没有「用户」概念，这里的 {@code langfuse.user.id} 只是把调用方标识原样透传，
     * 填的是业务侧给的字符串（可以是用户 id，也可以是租户 id、设备号）。</p>
     */
    public Span startRun(String agentName, String channel, String subjectId, Long sessionId,
                         String traceName, String inputPreview) {
        if (!enabled) {
            return Span.getInvalid();
        }
        try {
            AttributesBuilder attrs = Attributes.builder()
                    .put(OBSERVATION_TYPE, "agent")
                    .put("agent.name", nz(agentName))
                    .put("entry.channel", nz(channel))
                    .put(TAGS, nz(channel));
            if (traceName != null && !traceName.isBlank()) {
                attrs.put(TRACE_NAME, traceName);
            }
            if (subjectId != null && !subjectId.isBlank()) {
                attrs.put(USER_ID, subjectId);
            }
            if (sessionId != null && sessionId > 0) {
                attrs.put(SESSION_ID, String.valueOf(sessionId));
            }
            if (inputPreview != null && !inputPreview.isBlank()) {
                attrs.put("input.preview", inputPreview);
            }
            return tracer.spanBuilder("agent.run")
                    .setSpanKind(SpanKind.SERVER)
                    .setAllAttributes(attrs.build())
                    .startSpan();
        } catch (RuntimeException e) {
            log.debug("AiTracing#startRun#开 span 失败 err={}", e.toString());
            return Span.getInvalid();
        }
    }

    /** 一次模型调用 = 一个 generation，Langfuse 靠 observation.type 分类 */
    public Span startLlm(Span parent, String provider, String model, String purpose) {
        if (!enabled) {
            return Span.getInvalid();
        }
        try {
            return tracer.spanBuilder("llm.chat")
                    .setParent(parentContext(parent))
                    .setAllAttributes(Attributes.builder()
                            .put(OBSERVATION_TYPE, "generation")
                            .put(GEN_AI_SYSTEM, nz(provider))
                            .put(GEN_AI_MODEL, nz(model))
                            .put("llm.purpose", nz(purpose))
                            .build())
                    .startSpan();
        } catch (RuntimeException e) {
            return Span.getInvalid();
        }
    }

    public Span startTool(Span parent, String toolName, String argsJson) {
        if (!enabled) {
            return Span.getInvalid();
        }
        try {
            AttributesBuilder attrs = Attributes.builder()
                    .put(OBSERVATION_TYPE, "tool")
                    .put(TOOL_NAME, nz(toolName));
            if (argsJson != null && !argsJson.isBlank()) {
                attrs.put("tool.args", argsJson);
            }
            return tracer.spanBuilder("tool." + nz(toolName))
                    .setParent(parentContext(parent))
                    .setAllAttributes(attrs.build())
                    .startSpan();
        } catch (RuntimeException e) {
            return Span.getInvalid();
        }
    }

    // ---------------- 结果回填 ----------------

    /**
     * 旧签名：没有原文可写时（异常兜底、单测）仍按原来的方式收尾。
     * 保留它是为了不让观测改动扩散到所有调用点，也是「埋点永不抛异常」的一部分。
     */
    public void endLlm(Span span, int inputTokens, int outputTokens, double costCny,
                       String finishStatus, String errorCode) {
        endLlm(span, inputTokens, outputTokens, costCny, finishStatus, errorCode, null, null);
    }

    /** generation 收尾：prompt 与 completion 写进 Langfuse 的 Input / Output 主字段 */
    public void endLlm(Span span, int inputTokens, int outputTokens, double costCny,
                       String finishStatus, String errorCode, String inputText, String outputText) {
        if (span == null || !span.getSpanContext().isValid()) {
            return;
        }
        try {
            span.setAttribute(GEN_AI_INPUT, (long) inputTokens);
            span.setAttribute(GEN_AI_OUTPUT, (long) outputTokens);
            if (costCny > 0) {
                span.setAttribute(GEN_AI_COST, costCny);
            }
            if (errorCode != null) {
                span.setAttribute("error.code", errorCode);
                span.setStatus(StatusCode.ERROR, errorCode);
            }
            putIo(span, OBSERVATION_INPUT, inputText);
            putIo(span, OBSERVATION_OUTPUT, outputText);
            span.end();
        } catch (RuntimeException e) {
            log.debug("AiTracing#endLlm#收尾失败 err={}", e.toString());
        }
    }

    public void endTool(Span span, boolean success, Integer rows, long durationMs) {
        endTool(span, success, rows, durationMs, null, null);
    }

    /** tool 收尾：入参与工具返回数据写进 Input / Output 主字段 */
    public void endTool(Span span, boolean success, Integer rows, long durationMs,
                        String inputJson, String outputData) {
        if (span == null || !span.getSpanContext().isValid()) {
            return;
        }
        try {
            span.setAttribute(TOOL_STATUS, success ? "OK" : "FAIL");
            if (rows != null) {
                span.setAttribute(TOOL_ROWS, (long) rows);
            }
            span.setAttribute("tool.duration_ms", durationMs);
            putIo(span, OBSERVATION_INPUT, inputJson);
            putIo(span, OBSERVATION_OUTPUT, outputData);
            span.end();
        } catch (RuntimeException e) {
            log.debug("AiTracing#endTool#收尾失败 err={}", e.toString());
        }
    }

    public void endRun(Span span, String status, String errorCode, int steps,
                       int inputTokens, int outputTokens, double costCny, long durationMs) {
        endRun(span, status, errorCode, steps, inputTokens, outputTokens, costCny, durationMs, null, null);
    }

    /**
     * 根 span 收尾：用户输入与最终回复写进 Input / Output 主字段。
     * Langfuse v4 已废弃 trace 级 input/output，trace 详情的 Input/Output 就是读根 span 的这两个属性，
     * 所以必须写在根 span 上，写在子 span 上不会出现在 trace 详情里。
     */
    public void endRun(Span span, String status, String errorCode, int steps,
                       int inputTokens, int outputTokens, double costCny, long durationMs,
                       String inputText, String outputText) {
        if (span == null || !span.getSpanContext().isValid()) {
            return;
        }
        try {
            span.setAttribute("run.status", nz(status));
            span.setAttribute("run.steps", steps);
            span.setAttribute("run.duration_ms", durationMs);
            span.setAttribute(GEN_AI_INPUT, (long) inputTokens);
            span.setAttribute(GEN_AI_OUTPUT, (long) outputTokens);
            if (costCny > 0) {
                span.setAttribute(GEN_AI_COST, costCny);
            }
            if (errorCode != null) {
                span.setAttribute("error.code", errorCode);
                span.setStatus(StatusCode.ERROR, errorCode);
            } else {
                span.setStatus(StatusCode.OK);
            }
            putIo(span, OBSERVATION_INPUT, inputText);
            putIo(span, OBSERVATION_OUTPUT, outputText);
            span.end();
        } catch (RuntimeException e) {
            log.debug("AiTracing#endRun#收尾失败 err={}", e.toString());
        }
    }

    // ---------------- Input / Output ----------------

    /**
     * 写 Input / Output 主字段。开关关掉或文本为空时静默跳过——观测是旁路，
     * 这里任何失败都不能影响 span 结束，更不能冒到业务线程。
     */
    private void putIo(Span span, AttributeKey<String> key, String raw) {
        if (!ioEnabled() || raw == null || raw.isBlank()) {
            return;
        }
        try {
            span.setAttribute(key, ioPayload(raw));
        } catch (RuntimeException e) {
            log.debug("AiTracing#putIo#写 IO 属性失败 key={} err={}", key.getKey(), e.toString());
        }
    }

    /**
     * Langfuse 要求 input/output 是 <b>JSON 字符串</b>，直接塞中文纯文本会被当成非法 JSON。
     * 已经是 JSON 容器（工具入参、工具返回）就原样交给平台解析成结构化对象；
     * 其余包成 {@code {"text": ...}}。超长按配置截断并打 {@code truncated} 标记，
     * 截断后不再是合法 JSON 的对象会自动走包装分支，不会产出半个坏 JSON。
     */
    private String ioPayload(String raw) {
        int max = Math.max(0, properties.getTraceIoChars());
        boolean truncated = max > 0 && raw.length() > max;
        String text = truncated ? raw.substring(0, max) + "…" : raw;
        String trimmed = text.strip();
        boolean jsonContainer = (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
        if (jsonContainer && !truncated) {
            return trimmed;
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("text", text);
        if (truncated) {
            wrapper.put("truncated", true);
        }
        return Json.toJson(wrapper);
    }

    /** 读 traceId 回写 MySQL：这是"报表 → 瀑布图"能点进去的唯一凭据 */
    public String traceId(Span span) {
        if (span == null) {
            return null;
        }
        try {
            return span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static io.opentelemetry.context.Context parentContext(Span parent) {
        if (parent == null || !parent.getSpanContext().isValid()) {
            return io.opentelemetry.context.Context.root();
        }
        return io.opentelemetry.context.Context.root().with(parent);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** 兜底：GlobalOpenTelemetry 只在明确需要时用（例如 CLI 里手工初始化了 SDK） */
    static AiTracing fromGlobal() {
        try {
            return new AiTracing(GlobalOpenTelemetry.get(), new ObsProperties());
        } catch (RuntimeException e) {
            return NOOP;
        }
    }
}
