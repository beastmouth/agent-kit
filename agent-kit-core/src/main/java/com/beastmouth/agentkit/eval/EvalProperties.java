package com.beastmouth.agentkit.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 质量闭环配置，挂在 {@code agent-kit.eval} 下。
 *
 * <p>默认值遵守评审决议第 6 条：<b>judge 模型未配置时自动跳过 LLM 评审、只跑规则</b>，
 * 且必须告警（不是静默跳过）——静默跳过会让人误以为"质量在评"，实际只有规则那一层。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent-kit.eval")
public class EvalProperties {

    /** 总开关。关掉后只写 ai_run，不产生任何评分行 */
    private boolean enabled = true;

    /** 线上抽样比例（0~1）。只对"规则判定为可疑"的样本额外抽样送评审 */
    private double sampleRate = 0.05;

    /** LLM 评审开关。judge-model 为空时即使打开也会跳过并告警 */
    private boolean judgeEnabled = false;

    /** judge 用哪个渠道（留空 = 当前主渠道） */
    private String judgeProvider = "";

    /** judge 模型名，必须是 API id（如 deepseek-v4-pro）。留空 = 不评审 */
    private String judgeModel = "";

    /**
     * <b>线上评审</b>的 rubric 版本（judge / 规则引擎用）。改评分规则必须升版本，
     * 否则历史的分数不可比。
     *
     * <p>v1 → v2：四维之外先判「回复形态」（answered/clarified/refused/failed），
     * 澄清与合理拒答不再按「没给答案」扣分。同一个样本在 v1 下 goal=0、v2 下 goal=1 是
     * <b>预期行为</b>，跨版本比较必须先按 rubric_version 切开。</p>
     *
     * <p>注意<b>不覆盖黄金集</b>：黄金集的判定口径在代码里（{@link GoldenRubric}），
     * 版本号也由 {@code GoldenRubric.VERSION} 自己管。两条线的 rubric 是两套东西，
     * 混用一个配置项会出「代码改了、环境变量盖回去、分数盖成旧版本」这种事。</p>
     */
    private String rubricVersion = "v2";

    /** 评审时工具结果摘要最多带多少字：给 judge 看太多会诱导它照着复述 */
    private int judgeEvidenceChars = 800;

    // ---------------- 黄金集 ----------------

    /** 是否允许跑黄金集。默认关：它会在真库里写数据，只能由 CI/人工显式触发 */
    private boolean goldenEnabled = false;

    /** 黄金集用哪个调用方标识跑（业务侧自由约定，工具里若需要分组/限流就靠它） */
    private String goldenSubject = "golden-runner";

    /** 黄金集每条用例跑几遍（pass^k 用；跑 3 遍比多加 50 条用例更能反映稳定性） */
    private int goldenRepeat = 1;

    /**
     * 门禁档位：{@code off} 只跑不卡；{@code non-regression} 只卡"比基线低"（决议第 5 条）。
     * 稳定两周后再考虑绝对阈值，所以这里刻意不提供绝对阈值选项。
     */
    private String goldenGate = "non-regression";

    /** 允许相对基线下降多少（0.02 = 2 个百分点） */
    private double goldenMaxDrop = 0.02;
}
