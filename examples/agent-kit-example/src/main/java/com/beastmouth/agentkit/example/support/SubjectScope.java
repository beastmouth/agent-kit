package com.beastmouth.agentkit.example.support;

import com.beastmouth.agentkit.context.RunContext;

import java.util.Map;

/**
 * 「调用方是谁」这一层适配：把外部世界的身份翻译成脚手架认识的 {@link RunContext.Subject}。
 *
 * <p><b>为什么这层必须由业务写</b>：脚手架不知道你的登录态存在哪——是 Cookie、JWT、
 * IM 回调里的 {@code open_id}、还是一台设备的序列号。它只要一个不透明字符串。</p>
 *
 * <h3>用法（唯一正确姿势）</h3>
 * <pre>{@code
 * try (SubjectScope ignored = SubjectScope.open(SubjectScope.fromHeader("u-42", "web"))) {
 *     // 整条链路：会话、记忆、工具、观测
 * }
 * }</pre>
 *
 * <p><b>为什么必须 try-with-resources</b>：{@code RunContext} 底层是
 * {@code TransmittableThreadLocal}，线程池复用时不清理就会串号——A 的请求把 subject 留在
 * 线程上，下一次 B 的请求直接冒用 A 的身份写数据，而且不报错。这类 bug 只在压测时显现，
 * 排查成本极高。所以这个类刻意只提供 {@code AutoCloseable} 的入口，逼调用方写 close。</p>
 *
 * <p>实现上就是把 {@code set / clear} 包一层，没有别的心思。业务侧如果已经有自己的
 * ThreadLocal 上下文管理器，直接在 {@code finally} 里调 {@code RunContext.clear()} 也行，
 * 不必引这个类。</p>
 */
public final class SubjectScope implements AutoCloseable {

    /** 渠道标签的键名，落进 {@code ai_run.labels}，方便按端分组统计 */
    public static final String LABEL_CHANNEL = "channel";
    /** 角色标签，{@link DemoToolInterceptor} 会读它做准入判定 */
    public static final String LABEL_ROLE = "role";

    private SubjectScope() {
    }

    /**
     * 绑定调用方并返回可关闭句柄。
     *
     * @param subject 调用方标识；传 {@code null} 等价于「没有调用方」（CLI、定时任务），
     *                后续观测的 {@code subject_id} 落 NULL，工具里也取不到人
     */
    public static SubjectScope open(RunContext.Subject subject) {
        RunContext.set(subject);
        return new SubjectScope();
    }

    /**
     * 从 Web 请求头解析调用方——示例用最土的方式，真实项目换成你的鉴权结果。
     *
     * <p>示例约定两个头：{@code X-Subject-Id}（必填）、{@code X-Subject-Role}
     * （{@code owner} / {@code viewer}，缺省 owner）。<b>这只是示例约定</b>：
     * 真实项目这里应该从 JWT 解析后的 SecurityContext 里取，不要信任客户端自报的头。</p>
     */
    public static RunContext.Subject fromHeader(String subjectId, String role, String channel) {
        if (subjectId == null || subjectId.isBlank()) {
            return null;
        }
        return RunContext.Subject.of(subjectId.trim(), Map.of(
                LABEL_ROLE, role == null || role.isBlank() ? "owner" : role.trim(),
                LABEL_CHANNEL, channel == null || channel.isBlank() ? "web" : channel.trim()));
    }

    @Override
    public void close() {
        RunContext.clear();
    }
}
