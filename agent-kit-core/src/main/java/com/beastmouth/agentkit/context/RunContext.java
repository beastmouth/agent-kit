package com.beastmouth.agentkit.context;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次运行的「调用方标识」：谁发起了这次运行。
 *
 * <p><b>为什么只用一个不透明字符串</b>：脚手架不认识的业务系统太多——有的是用户 id，
 * 有的是工单号，有的是外部设备号，有的干脆没有调用方（CLI、定时任务）。
 * 一旦这里出现 {@code userId} / {@code tenantId} 这样的具体字段，就等于把别人的
 * 账号体系搬进了脚手架：字段要么冗余、要么不够用，还得跟着业务表改。</p>
 *
 * <p>所以只保留两样东西：</p>
 * <ul>
 *   <li>{@code id}：调用方标识，<b>对脚手架完全透明</b>。它只进观测日志的 {@code subject_id} 列，
 *       不做任何解析、不做权限判断。没有调用方时传 {@code null}。</li>
 *   <li>{@code labels}：调用方自带的一组标签（如 {@code {"channel":"feishu"}}），
 *       随 run 一起落到 {@code labels} 列，供业务侧自行分组统计。<b>不要放手机号、身份证等敏感信息</b>——
 *       它会进数据库与 trace。</li>
 * </ul>
 *
 * <h3>为什么做成静态上下文</h3>
 * <p>工具的执行点在业务代码里，那些方法签名不会为了埋点额外带参数；观测层（{@code RunScope}）
 * 与工具拦截器都在框架回调里，只能从「当前线程」取标识。</p>
 *
 * <h3>为什么用 TransmittableThreadLocal</h3>
 * <p>Agent 的流式链路常年跑在 {@code boundedElastic} 这类线程池上，线程池<b>不继承</b>
 * ThreadLocal，上下文一丢观测就断成两截。TTL 能解决的前提是线程池用
 * {@code TtlExecutors} 包装过；<b>没包装时它退化得和 ThreadLocal 一样</b>。
 * 因此更稳的做法仍然是在提交任务处显式传递，上下文只是兜底。</p>
 */
public final class RunContext {

    /**
     * 调用方标识 + 自定义标签。
     *
     * @param id     调用方标识，可为 {@code null}；脚手架不解析它
     * @param labels 自定义标签，可为空 Map；会落到 {@code ai_run.labels}
     */
    public record Subject(String id, Map<String, Object> labels) {

        public Subject {
            labels = labels == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(labels));
        }

        public static Subject of(String id) {
            return new Subject(id, Map.of());
        }

        public static Subject of(String id, Map<String, Object> labels) {
            return new Subject(id, labels);
        }

        /** 取一个标签，不存在返回 null */
        public Object label(String key) {
            return labels.get(key);
        }
    }

    private static final TransmittableThreadLocal<Subject> CURRENT = new TransmittableThreadLocal<>();

    private RunContext() {
    }

    /** 入口处绑定；必须配 {@link #clear()} 的 finally，否则线程池复用会串号 */
    public static void set(Subject subject) {
        if (subject == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(subject);
        }
    }

    /** 当前标识；CLI、定时任务、单测里可能为 null，调用方必须容忍 */
    public static Subject current() {
        return CURRENT.get();
    }

    /** 当前调用方 id，未设置返回 null */
    public static String subjectId() {
        Subject subject = CURRENT.get();
        return subject == null ? null : subject.id();
    }

    /** 取一个标签，未设置返回 null */
    public static Object label(String key) {
        Subject subject = CURRENT.get();
        return subject == null ? null : subject.label(key);
    }

    /** 必须在入口的 finally 里调用 */
    public static void clear() {
        CURRENT.remove();
    }
}
