package com.beastmouth.agentkit.tool;

import java.util.Map;

/**
 * Agent 可调用的一个能力。
 *
 * <p>工具是 Agent 与业务之间的唯一边界：模型只能看到 {@link #name()} / {@link #description()} /
 * {@link #parametersSchema()}，真正执行时进入 Java 方法，复用现有 Service 的事务、锁、鉴权与审计。</p>
 *
 * <p>新增工具：写一个 {@code @Component implements AgentTool}，再在对应子 Agent 的
 * {@link #name()} 白名单里登记即可，无需改调度逻辑。</p>
 */
public interface AgentTool {

    /** 全局唯一工具名，模型按它回传，建议 {@code 领域_动作}，如 {@code account_create} */
    String name();

    /** 给模型看的能力说明，写清「什么时候该用」而不是「怎么实现」 */
    String description();

    /** 参数的 JSON Schema 文本 */
    String parametersSchema();

    /** 执行，返回结构化结果 */
    ToolOutcome execute(Map<String, Object> args);

    /**
     * 工具执行结果。
     *
     * @param success 是否成功
     * @param summary 一句话结果，直接展示给用户，也会回灌给模型
     * @param data    结构化数据（JSON 串），可为 null
     * @param rows    影响/返回的条数，<b>可空</b>。见下方说明，别乱填 0
     */
    record ToolOutcome(boolean success, String summary, String data, Integer rows) {

        /** 兼容旧调用：不关心条数时用这个，落库记 NULL 而不是 0 */
        public ToolOutcome(boolean success, String summary, String data) {
            this(success, summary, data, null);
        }

        public static ToolOutcome ok(String summary, String data) {
            return new ToolOutcome(true, summary, data, null);
        }

        /**
         * 返回条数明确的成功结果。
         *
         * <p><b>为什么要单独传：</b>「工具查了 0 条，模型却答了一段像样的结论」是这套系统里
         * 最值钱的幻觉信号——它不会抛异常、不会有错误码、HTTP 还是 200。只有拿到条数，
         * 才能把这类回答从「成功」里挑出来。</p>
         *
         * <p><b>约定（重要）：</b></p>
         * <ul>
         *   <li>查询类：传实际命中条数，<b>0 就是 0</b>，这正是要抓的；</li>
         *   <li>写入类：传影响行数（新增/更新/删除了几条）；</li>
         *   <li>无关的（打招呼、澄清反问、纯计算）：传 {@code null}，<b>不要传 0</b>，
         *       否则会被误判成「空结果」。</li>
         * </ul>
         */
        public static ToolOutcome ok(String summary, String data, Integer rows) {
            return new ToolOutcome(true, summary, data, rows);
        }

        public static ToolOutcome fail(String summary) {
            return new ToolOutcome(false, summary, null, null);
        }
    }
}
