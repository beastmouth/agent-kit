package com.beastmouth.agentkit.agentscope.bridge;

import com.alibaba.fastjson2.JSON;
import com.beastmouth.agentkit.context.RunContext;
import com.beastmouth.agentkit.obs.ObsHub;
import com.beastmouth.agentkit.obs.RunScope;
import com.beastmouth.agentkit.obs.ToolCallRecorder;
import com.beastmouth.agentkit.tool.AgentTool;
import com.beastmouth.agentkit.tool.ToolInterceptor;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolExecutionContext;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把脚手架的 {@link AgentTool} 桥接成 AgentScope 的工具。
 *
 * <p><b>为什么用适配器而不是把工具改写成 {@code @Tool} 注解</b>：注解方式要求每个参数
 * 都补 {@code @ToolParam}，否则生成的 schema 里 {@code properties} 是空的，模型调用后
 * 参数全为 null。而脚手架工具的 schema 本来就是手写 JSON 文本、执行入口是
 * {@code execute(Map)}——与框架提供的 {@code AgentTool} 接口形状几乎一一对应，
 * 包一层即可原样复用，业务逻辑一个字都不用改。</p>
 *
 * <p><b>上下文透传是这里的核心职责。</b>工具实际执行在 Reactor 的 {@code boundedElastic}
 * 线程，而 {@link RunContext} 是 TransmittableThreadLocal——线程池没被 {@code TtlExecutors}
 * 包装过时，在那个线程上读出来是 {@code null}。后果不是「少个参数」这么轻：
 * 依赖调用方标识做数据隔离的系统会静默退化成「查全表」。</p>
 *
 * <p>因此每次调用都从 {@link ToolExecutionContext} 取回调用方标识，临时绑到当前线程，
 * 执行完在 {@code finally} 里恢复原值。调用方（{@code AgentScopeEngine}）负责在每次请求的
 * {@code RuntimeContext} 里放入标识、scope 与语言。</p>
 *
 * <p><b>拦截器也在这里跑</b>：{@link ToolInterceptor} 必须在工具执行线程上执行，
 * 否则拦截器里 {@code RunContext.current()} 取不到人，权限判定会退化成「全放行」。
 * 所以顺序是「先绑上下文，再过拦截器，最后调工具」，一步都不能换。</p>
 */
@Slf4j
public class ToolBridge implements io.agentscope.core.tool.AgentTool {

    /** RuntimeContext / ToolExecutionContext 中的键名，与 {@code AgentScopeEngine} 一处定义两处用 */
    public static final String CTX_SUBJECT = "subject";

    /**
     * 本次请求的观测上下文。
     *
     * <p>与调用方标识一样走 RuntimeContext 而不是 ThreadLocal：工具在 Reactor 的
     * {@code boundedElastic} 上执行，TTL 读不到；而 RunScope 一旦丢失，工具明细就静默不落库
     * （表现是 {@code ai_run} 有工具调用次数、{@code ai_tool_call} 一条都没有）。</p>
     */
    public static final String CTX_RUN_SCOPE = "runScope";

    /**
     * 本次请求的语言（{@code zh-CN} / {@code en-US}）。
     *
     * <p>同一类问题：{@code LocaleContextHolder} 也是 ThreadLocal，工具跑到
     * {@code boundedElastic} 上就回落到 JVM 默认语言。后果很隐蔽——按语言分片的缓存
     * 会给英文用户拼出中文 key，于是<b>看到中文结果，还把中文写进中文缓存</b>，
     * 既不报错也不影响中文用户。</p>
     */
    public static final String CTX_LANG = "lang";

    /** 没有注入配置时的入参截断长度，与 {@code agent-kit.obs.args-chars} 的默认值一致 */
    private static final int DEFAULT_ARGS_CHARS = 1024;

    private final AgentTool delegate;
    private final Map<String, Object> parameters;
    private final int argsChars;
    private final List<ToolInterceptor> interceptors;

    public ToolBridge(AgentTool delegate, int argsChars, List<ToolInterceptor> interceptors) {
        this.delegate = delegate;
        this.argsChars = argsChars > 0 ? argsChars : DEFAULT_ARGS_CHARS;
        this.interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
        this.parameters = parseSchema(delegate);
    }

    /**
     * 预解析一次 schema，不在每次调用时重复解析。
     *
     * <p>解析失败不抛异常而是降级为空参数——宁可这个工具的参数不可见（模型会少传参、
     * 业务侧报错可见），也不要让整个 Agent 构造失败导致所有工具都不可用。</p>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSchema(AgentTool tool) {
        try {
            Map<String, Object> parsed = JSON.parseObject(tool.parametersSchema(), Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            log.error("ToolBridge#parseSchema#schema 不是合法 JSON，该工具将不可用 name={} err={}",
                    tool.name(), e.toString());
            return Map.of();
        }
    }

    @Override
    public String getName() {
        return delegate.name();
    }

    @Override
    public String getDescription() {
        return delegate.description();
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 工具是同步阻塞的（内部走 MyBatis/HTTP），用 fromCallable 让它在订阅线程上执行，
        // 不要在 Reactor 的 event loop 上直接跑
        return Mono.fromCallable(() -> invoke(param));
    }

    private ToolResultBlock invoke(ToolCallParam param) {
        Map<String, Object> args = param.getInput() == null ? new HashMap<>() : param.getInput();
        String toolId = param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();

        RunContext.Subject previousSubject = RunContext.current();
        Locale previousLocale = LocaleContextHolder.getLocale();
        // 顺序不能换：拦截器与工具都可能读 RunContext，必须先绑
        applyContext(param.getContext());

        RunScope scope = resolveScope(param.getContext());
        String argsJson = argsJson(args);
        Span span = ObsHub.tracing().startTool(scope.getSpan(), delegate.name(), argsJson);
        long startedAt = System.nanoTime();
        AgentTool.ToolOutcome outcome = null;
        try {
            outcome = execute(args);
            return toBlock(toolId, outcome);
        } catch (RuntimeException e) {
            // 工具内部已经把业务异常转成 ToolOutcome.fail，能走到这里说明是没兜住的运行时异常。
            // 不把栈丢给模型，只回一句可读的失败，同时留完整日志给排查。
            log.error("ToolBridge#invoke#工具执行异常 name={} args={}", delegate.name(), args, e);
            outcome = AgentTool.ToolOutcome.fail("工具执行失败：" + e.getMessage());
            return toBlock(toolId, outcome);
        } finally {
            long durationMs = millisSince(startedAt);
            AgentTool.ToolOutcome finalOutcome = outcome != null
                    ? outcome : AgentTool.ToolOutcome.fail("执行失败");
            // 失败与被拦截都要留一条明细：工具异常率是告警项，漏记会让错误率永远偏低
            ToolCallRecorder.record(scope, delegate.name(), argsJson, finalOutcome.success(),
                    finalOutcome.rows(), durationMs, null, finalOutcome.data(), span, argsChars);
            notifyAfter(args, finalOutcome, durationMs);
            restore(previousSubject, previousLocale);
        }
    }

    /**
     * 过拦截器链，再调工具本体。
     *
     * <p>拦截器由业务提供（权限、审计、限流都在这里做），脚手架不认识任何策略。
     * 拦截返回的原因会原样回灌给模型，让模型如实转述而不是反复重试。</p>
     */
    private AgentTool.ToolOutcome execute(Map<String, Object> args) {
        for (ToolInterceptor interceptor : interceptors) {
            String rejection;
            try {
                rejection = interceptor.before(delegate.name(), args);
            } catch (RuntimeException e) {
                // 判定失败按放行处理：拦住是「用户做不成事」，放行最多是「没拦住」
                log.warn("ToolBridge#execute#拦截器判定失败，按放行处理 interceptor={} tool={} err={}",
                        interceptor.getClass().getSimpleName(), delegate.name(), e.toString());
                continue;
            }
            if (rejection != null && !rejection.isBlank()) {
                log.info("ToolBridge#execute#工具被拦截 tool={} interceptor={} reason={}",
                        delegate.name(), interceptor.getClass().getSimpleName(), rejection);
                return AgentTool.ToolOutcome.fail(rejection + "。请如实告诉用户，不要重复尝试。");
            }
        }
        return delegate.execute(args);
    }

    /** 通知拦截器结果。埋点与审计不能因为某个拦截器写崩了就中断后面的 */
    private void notifyAfter(Map<String, Object> args, AgentTool.ToolOutcome outcome, long durationMs) {
        for (ToolInterceptor interceptor : interceptors) {
            try {
                interceptor.after(delegate.name(), args, outcome, durationMs);
            } catch (RuntimeException e) {
                log.warn("ToolBridge#notifyAfter#拦截器收尾失败 interceptor={} tool={} err={}",
                        interceptor.getClass().getSimpleName(), delegate.name(), e.toString());
            }
        }
    }

    /**
     * 取本次请求的 RunScope。
     *
     * <p>先看请求上下文（跨线程可靠），再退回 TTL（同线程直调时才有值）。
     * 都拿不到时 {@code RunScope.current()} 返回 NOOP，埋点自动降级而不是抛异常。</p>
     */
    private RunScope resolveScope(ToolExecutionContext ctx) {
        try {
            if (ctx != null && ctx.contains(CTX_RUN_SCOPE, RunScope.class)) {
                RunScope scope = ctx.get(CTX_RUN_SCOPE, RunScope.class);
                if (scope != null) {
                    return scope;
                }
            }
        } catch (RuntimeException e) {
            log.debug("ToolBridge#resolveScope#取观测上下文失败 name={} err={}", delegate.name(), e.toString());
        }
        return RunScope.current();
    }

    /** 入参转 JSON：序列化失败返回 null，不影响工具本身 */
    private String argsJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        try {
            return JSON.toJSONString(args);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 把请求级上下文装载到当前线程。
     *
     * <p>取不到调用方标识时<b>不抛异常</b>：有些工具确实不依赖调用方（换算汇率、查字典），
     * 依赖的那些自己会报错，错误信息更贴近业务。但必须留 WARN——静默放行是这类 bug
     * 唯一的藏身处。</p>
     */
    private void applyContext(ToolExecutionContext ctx) {
        if (ctx == null) {
            log.warn("ToolBridge#applyContext#缺少 ToolExecutionContext，工具将在无调用方上下文下执行 name={}",
                    delegate.name());
            return;
        }
        RunContext.Subject subject = ctx.contains(CTX_SUBJECT, RunContext.Subject.class)
                ? ctx.get(CTX_SUBJECT, RunContext.Subject.class) : null;
        if (subject == null) {
            log.warn("ToolBridge#applyContext#调用方上下文缺失 name={}", delegate.name());
        }
        RunContext.set(subject);

        // 语言同样要摆正：拿不到就保持调用线程原有的 Locale（宁可沿用上层已解析的值，
        // 也不要在这里硬写成中文——那正是这个 bug 的成因）
        String lang = ctx.contains(CTX_LANG, String.class) ? ctx.get(CTX_LANG, String.class) : null;
        if (lang != null && !lang.isBlank()) {
            LocaleContextHolder.setLocale(Locale.forLanguageTag(lang));
        }
    }

    /** 恢复调用前的线程状态。注意 set(null) 等价于 remove，不能写成 if (x != null) 才清 */
    private void restore(RunContext.Subject previousSubject, Locale previousLocale) {
        RunContext.set(previousSubject);
        if (previousLocale == null) {
            LocaleContextHolder.resetLocaleContext();
        } else {
            LocaleContextHolder.setLocale(previousLocale);
        }
    }

    private ToolResultBlock toBlock(String toolId, AgentTool.ToolOutcome outcome) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("success", outcome.success());
        // rows 刻意只在非空时才写：0 表示「查了但没数据」（质量信号），null 表示
        // 「这个工具不产条数」，混淆两者会让空结果率永远算错。
        // 注意不能把 null 塞进 metadata——框架内部会 Map.copyOf，遇到 null 直接 NPE。
        if (outcome.rows() != null) {
            metadata.put("rows", outcome.rows());
        }
        if (outcome.data() != null) {
            metadata.put("data", outcome.data());
        }
        return ToolResultBlock.builder()
                .id(toolId)
                .name(delegate.name())
                .output(TextBlock.builder().text(outcome.summary() == null ? "" : outcome.summary()).build())
                .metadata(metadata)
                .state(outcome.success() ? ToolResultState.SUCCESS : ToolResultState.ERROR)
                .build();
    }
}
