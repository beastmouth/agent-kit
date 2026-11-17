package com.beastmouth.agentkit.eval;

/**
 * 规则判定结果。
 *
 * @param label    结论标签（优先级最高的那条规则）
 * @param rule     命中的规则 id，写进 {@code ai_eval_score.detail_json}，方便回溯"为什么判它失败"
 * @param evidence 证据片段（工具名 / 命中的措辞），只留短的，别把用户原话整段抄进评分表
 */
public record QualityResult(QualityLabel label, String rule, String evidence) {

    public static QualityResult of(QualityLabel label, String rule, String evidence) {
        return new QualityResult(label, rule, evidence);
    }

    public boolean success() {
        return label.success();
    }
}
