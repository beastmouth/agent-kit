package com.beastmouth.agentkit.tool;

import java.util.Map;

/**
 * 工具执行的横切扩展点。
 *
 * <p><b>脚手架不内置任何策略</b>：权限校验、审计留痕、限流熔断、参数脱敏、灰度开关
 * 都是「具体系统」的事，各自的判定依据（角色表？工单状态？配额？）脚手架不可能知道。
 * 这里只提供时机，不提供语义。</p>
 *
 * <p>注册方式：把实现声明成 Spring Bean 即可，可以有多个，按 {@code getOrder()}
 * 从低到高依次执行；任何一个 {@link #before} 返回非空字符串就短路，把该字符串作为
 * 失败原因回灌给模型（模型会如实告诉用户，而不是重试）。</p>
 *
 * <p><b>典型用法</b>：</p>
 * <pre>{@code
 * @Component
 * public class PermissionInterceptor implements ToolInterceptor {
 *     public String before(String tool, Map<String, Object> args) {
 *         return currentUserCan(tool) ? null : "当前账号没有该操作权限";
 *     }
 * }
 * }</pre>
 *
 * <p>实现必须<b>永不抛异常</b>：抛出的异常会被当作工具调用失败处理，用户看到的是一次
 * 莫名其妙的报错。拿不准时返回 {@code null}（放行）比拦住更安全。</p>
 */
public interface ToolInterceptor {

    /**
     * 工具执行前。
     *
     * @param toolName 工具名
     * @param args     模型给出的参数（只读，不要修改）
     * @return {@code null} 表示放行；返回非空字符串表示拒绝，字符串会作为原因回灌给模型
     */
    default String before(String toolName, Map<String, Object> args) {
        return null;
    }

    /**
     * 工具执行后（成功或失败都会调用，包括被 {@link #before} 拦下的情况）。
     *
     * @param durationMs 工具实际耗时；被拦截时为 0
     */
    default void after(String toolName, Map<String, Object> args,
                       AgentTool.ToolOutcome outcome, long durationMs) {
    }

    /** 空实现，仅在需要占位的场合使用 */
    ToolInterceptor NONE = new ToolInterceptor() {
    };
}
