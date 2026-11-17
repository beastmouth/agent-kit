package com.beastmouth.agentkit.eval;

import com.alibaba.fastjson2.JSONObject;

import java.util.Locale;

/**
 * LLM 评审的结论（rubric v2），以及「从结论推出四个维度」的口径。
 *
 * <h3>为什么要先判形态，再判分数</h3>
 * <p>v1 的四个维度是平铺的：faithful / instruction / complete / goal。它对「答了但答错」很灵敏，
 * 对「反问」却是错的——用户问「上个月 A 类多少」，工具结果里没有 A 类，助手回
 * 「没有 A 类，现有 B、C 两类，你要查 B 吗」，这是<b>正确行为</b>，
 * 但 v1 的口径会把它读成「该给的明细没给」→ complete=0、「目标没达成」→ goal=0。
 * 结果就是：<b>澄清率高的那一批，质量分被系统性压低</b>，而这批恰恰是模型在守规矩。</p>
 *
 * <p>v2 先把回复归到四种形态之一（{@link Outcome}），再按形态填分数。澄清/拒答是否算通过，
 * 只由 {@code clarify_ok} 一个开关决定——它问的是「这次反问该不该发生、有没有用」，
 * 而不是「有没有直接给答案」。</p>
 *
 * <h3>为什么 goal / complete 由代码推、不采信模型</h3>
 * <p>「澄清算不算完成」是个<b>口径</b>问题，不是判断题。让模型自由发挥，同一个澄清样本
 * 十次能给出三种答案，比值就没法跨批次比。所以这里把口径写成代码：
 * 合理澄清 → goal=complete=1；不合理澄清 → 两个都 0；{@code outcome=failed} → 两个都 0。
 * 模型只负责它真正擅长的那件事：<b>这条回复属于哪种形态、这个反问该不该</b>。</p>
 *
 * <h3>向后兼容</h3>
 * <p>模型不吐 {@code outcome}（旧 prompt / 小模型忽略字段）时退化回 {@link Outcome#ANSWERED}，
 * 分数口径与 v1 完全一致——升级 rubric 不会让历史看板突然跳变，也不会因为模型偶尔漏字段
 * 就把一整批样本判成澄清。</p>
 *
 * @param outcome     回复形态，决定后面怎么算分
 * @param clarifyOk   澄清/拒答是否合理；非澄清形态恒为 0（无意义）
 * @param faithful    数字/结论能否被工具结果支撑
 * @param instruction 是否在回应用户问的那件事
 * @param complete    该给的信息是否给全（含义随 outcome 变）
 * @param goal        这一轮该完成的事是否完成（含义随 outcome 变）
 * @param hallucinated 工具失败或 0 条却声称"已办好/没有数据"
 * @param reason      20 字以内的判定理由
 */
public record JudgeVerdict(Outcome outcome,
                           int clarifyOk,
                           int faithful,
                           int instruction,
                           int complete,
                           int goal,
                           int hallucinated,
                           String reason) {

    /** 回复形态。顺序即评分优先级：先认清"它在干什么"，再决定"干得好不好" */
    public enum Outcome {

        /** 直接把用户要的答案给了（金额、明细、统计、写操作结果） */
        ANSWERED,

        /** 信息不足或条件对不上，助手指明缺什么并反问——正确行为，不是"没回答" */
        CLARIFIED,

        /** 请求超出助手能力（订票、发邮件、改密码），如实说明做不到 */
        REFUSED,

        /** 该答没答、答错、答非所问，或只把工具报错复述了一遍 */
        FAILED;

        /** 解析形态字符串；未知/缺失一律按 answered 处理，保证口径可回退 */
        static Outcome of(String raw) {
            if (raw == null || raw.isBlank()) {
                return ANSWERED;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ANSWERED;
            }
        }

        /** 是不是"反问/拒答"这一类：这两个形态不按"有没有给答案"评分 */
        public boolean interaction() {
            return this == CLARIFIED || this == REFUSED;
        }
    }

    /**
     * 从模型给的 JSON 推出结论。
     *
     * <p>三步：① 判形态；② 幻觉时强制 faithful=0（模型常常一边标幻觉一边给 faithful=1）；
     * ③ 澄清/拒答/失败三种形态的 complete 与 goal 由口径决定，不采信模型自由发挥。</p>
     */
    public static JudgeVerdict parse(JSONObject json) {
        Outcome outcome = Outcome.of(json == null ? null : json.getString("outcome"));
        int faithful = flag(json, "faithful");
        int instruction = flag(json, "instruction");
        int complete = flag(json, "complete");
        int goal = flag(json, "goal");
        int hallucinated = flag(json, "hallucinated");

        // 编造了数字还说"忠实"，是自相矛盾的组合，按更严重的那个算
        if (hallucinated == 1) {
            faithful = 0;
        }

        int clarifyOk = 0;
        if (outcome.interaction()) {
            // 模型漏字段时退回它给的 complete：只有明确"说清了为什么和下一步"才算合理澄清，
            // 缺字段不等于免罚——否则升级 rubric 反而会让偷懒的反问免检
            Integer raw = integer(json, "clarify_ok", "clarifyOk");
            clarifyOk = raw == null ? complete : (raw == 1 ? 1 : 0);
            complete = clarifyOk;
            goal = clarifyOk;
        } else if (outcome == Outcome.FAILED) {
            // 判成"没答好"就别再给分：模型常把 goal 留成 1（"它确实努力了"），那是同情不是评分
            complete = 0;
            goal = 0;
        }
        return new JudgeVerdict(outcome, clarifyOk, faithful, instruction, complete, goal, hallucinated,
                json == null ? null : json.getString("reason"));
    }

    /** 澄清/拒答的质量标签；普通回答返回 null（不写这一行，避免把基线摊薄） */
    public String interactionLabel() {
        return switch (outcome) {
            case CLARIFIED -> clarifyOk == 1 ? "CLARIFY_OK" : "CLARIFY_UNNEEDED";
            // 该做却拒：过度拒答，与"合理拒答"必须分开看，否则放宽阈值会掩盖能力退化
            case REFUSED -> clarifyOk == 1 ? "REFUSAL_OK" : "REFUSAL_UNNEEDED";
            default -> null;
        };
    }

    /** 给 {@code ai_eval_score_total{metric="judge"}} 用的标签：在看板上能直接拆出澄清率 */
    public String metricLabel() {
        if (hallucinated == 1) {
            return "HALLUCINATION";
        }
        return switch (outcome) {
            case CLARIFIED -> clarifyOk == 1 ? "REVIEWED_CLARIFY" : "REVIEWED_CLARIFY_BAD";
            case REFUSED -> clarifyOk == 1 ? "REVIEWED_REFUSAL" : "REVIEWED_REFUSAL_BAD";
            default -> "REVIEWED";
        };
    }

    private static int flag(JSONObject json, String key) {
        Integer value = json == null ? null : json.getInteger(key);
        return value != null && value == 1 ? 1 : 0;
    }

    private static Integer integer(JSONObject json, String... keys) {
        if (json == null) {
            return null;
        }
        for (String key : keys) {
            Integer value = json.getInteger(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
