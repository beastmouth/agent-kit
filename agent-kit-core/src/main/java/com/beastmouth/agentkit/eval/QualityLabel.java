package com.beastmouth.agentkit.eval;

/**
 * 质量标签：一次任务的"结论"。
 *
 * <p>只用一个字段表达结论，是为了让看板能直接 group by，而不用读一堆布尔字段拼条件。
 * 顺序即优先级——同一次任务命中多条规则时，取<b>最靠前</b>的那条，
 * 因为越靠前的越严重（幻觉让用户丢数据，超步只是慢了）。</p>
 */
public enum QualityLabel {

    /**
     * 幻觉成功：工具失败了，回复却说"已经记好了"。
     *
     * <p>红线。用户以为记上了、其实没记，等月底对账才发现——这是最伤信任的一类错。
     * 方案里定的告警阈值是 {@code > 0}，不是百分比。</p>
     */
    HALLUCINATION(false),

    /** 协议解析失败：模型输出不是合法 JSON，只能兜底 */
    PARSE_FAIL(false),

    /** 空回复 / 回复被截断：用户看到一片空白 */
    EMPTY_REPLY(false),

    /** 超过最大轮次：任务没收口就断了 */
    OVERSTEP(false),

    /** 命中固定兜底话术：说明上游已经放弃了这次任务 */
    FALLBACK_REPLY(false),

    /** 工具调用失败，且回复没有假装成功（至少是诚实的） */
    TOOL_FAIL(false),

    /** 工具返回 0 条，回复断言"没有这笔开销"——可能是对的，也可能是筛选条件写错 */
    EMPTY_ASSERT(true),

    /** 发生了厂商降级（主渠道挂了换备用）：不算失败，但要能从报表里看出比例 */
    FALLBACK_USED(true),

    /** 触发了澄清反问：正常行为，骤升才是问题 */
    CLARIFY(true),

    /** 没命中任何规则，按成功计 */
    OK(true);

    private final boolean success;

    QualityLabel(boolean success) {
        this.success = success;
    }

    /** 是否计入"成功任务"分母。注意分母只算成功，成本分子算全部——这是北极星指标的定义 */
    public boolean success() {
        return success;
    }
}
