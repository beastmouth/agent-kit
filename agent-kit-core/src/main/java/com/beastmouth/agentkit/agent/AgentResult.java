package com.beastmouth.agentkit.agent;

import java.util.List;

/**
 * 子 Agent 的执行结果。
 *
 * <p>这是主 Agent 唯一能看到的东西：一句话结论 + 执行过哪些动作。
 * 子 Agent 内部的提示词、工具往返、模型原始输出都不会外泄，避免污染上层上下文。</p>
 *
 * @param agentName 哪个子 Agent 处理的
 * @param reply     面向用户的结论
 * @param events    执行过的工具动作，按时间顺序
 * @param partial   这一轮是否「说到一半断了」：{@code reply} 是真实产出过的部分，
 *                  但不完整。上层据此决定落库标记、日志与回报口径——
 *                  <b>不能因为「有内容」就当成成功</b>，那会让报表里的成功率虚高
 */
public record AgentResult(String agentName, String reply, List<ToolEvent> events, boolean partial) {

    /** 兼容绝大多数调用点：正常完成的结果不用写第四个参数 */
    public AgentResult(String agentName, String reply, List<ToolEvent> events) {
        this(agentName, reply, events, false);
    }

    /**
     * 一次工具调用。
     *
     * @param name    工具名
     * @param success 是否成功
     * @param summary 结果摘要
     * @param rows    结果条数；工具没报（写操作、纯文本结果）时为 null。
     *                <b>0 与 null 含义完全不同</b>：0 表示"查了但没数据"，是质量信号；
     *                null 表示"这个工具不产出条数"。混淆两者，空结果率就永远算错。
     */
    public record ToolEvent(String name, boolean success, String summary, Integer rows) {

        /** 兼容旧调用点：不关心条数时不用写第四个参数 */
        public ToolEvent(String name, boolean success, String summary) {
            this(name, success, summary, null);
        }
    }
}
