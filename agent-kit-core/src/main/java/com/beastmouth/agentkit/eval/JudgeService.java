package com.beastmouth.agentkit.eval;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.beastmouth.agentkit.eval.mapper.EvalMapper;
import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.model.ModelReply;
import com.beastmouth.agentkit.obs.AiMetrics;
import com.beastmouth.agentkit.obs.IdGen;
import com.beastmouth.agentkit.obs.ObsHub;
import com.beastmouth.agentkit.obs.RunRow;
import com.beastmouth.agentkit.obs.ToolCallRow;
import com.beastmouth.agentkit.provider.ModelProvider;
import com.beastmouth.agentkit.provider.ModelProviderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM 评审（P3 第二层）：只判"语义对不对"，确定性规则判不了的那些。
 *
 * <h3>现在默认关着，且必须吵</h3>
 * <p>评审决议第 6 条：judge 模型由黄帮景后续提供。没配模型时<b>跳过并告警</b>——
 * 静默跳过最危险，看板上"评审样本 0 条"会被误读成"没有坏样本"。</p>
 *
 * <h3>给 judge 看什么、不看什么</h3>
 * <p>只给：用户原话 + 回复 + 工具名/状态/条数。不给：系统提示词、模型推理过程。
 * 给了推理过程，judge 会顺着模型的逻辑自我说服（"它想得挺周到的"），
 * 分数虚高，越优化越飘——这是方案 §5.7 专门写过的坑。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeService {

    /**
     * judge 的角色设定；改 rubric 必须同时升 {@code agent-kit.eval.rubric-version}。
     *
     * <p>v2 起多了一条硬要求：<b>先判形态，再判分数</b>。这不是措辞偏好——v1 把「反问」
     * 和「没回答」算成同一件事，导致澄清率高的批次被系统性扣分，见 {@link JudgeVerdict}。</p>
     */
    private static final String SYSTEM = """
            你是助手回答质量的评审员。只做判定，不重写答案。
            严格按照给定的 JSON 输出，不要输出任何解释性文字或 Markdown 代码块。
            """;

    /**
     * 质量评判专用的路由键：配了 {@code agent-kit.routes.judge} 就走它，没配则回落默认渠道。
     *
     * <p>评判是「另一个模型的判断」，跟被评判的对话链路混用同一个渠道会产生同源偏差
     * （同一个模型的毛病自己看不出来）。留一个固定键，让使用方可以把它指到不同厂商。</p>
     */
    public static final String JUDGE_ROUTE = "judge";

    private final EvalProperties properties;
    private final ModelProviderRegistry providerRegistry;
    private final EvalMapper mapper;

    private volatile boolean warned;

    /**
     * 评审一条任务，结果写 {@code ai_eval_score}。
     *
     * <p>在写库线程里调用；任何异常只降级为日志——评审失败不能影响已经入库的任务记录。</p>
     */
    public void review(RunRow row, List<ToolCallRow> tools) {
        if (row == null || !properties.isJudgeEnabled()) {
            return;
        }
        if (properties.getJudgeModel() == null || properties.getJudgeModel().isBlank()) {
            metrics().judgeSkipped("no_model");
            if (!warned) {
                warned = true;
                log.warn("JudgeService#review#未配置 agent-kit.eval.judge-model，LLM 评审已跳过，"
                        + "当前只有确定性规则在打分（决议第 6 条：模型待提供）");
            }
            return;
        }
        try {
            ModelProvider provider = providerRegistry.route(JUDGE_ROUTE);
            String prompt = buildPrompt(row, tools);
            ModelReply reply = provider.chat(
                    List.of(ChatMessage.system(SYSTEM), ChatMessage.user(prompt)), true);
            JSONObject json = JSON.parseObject(stripFence(reply.content()));
            if (json == null) {
                metrics().judgeSkipped("parse_fail");
                return;
            }
            persist(row, JudgeVerdict.parse(json));
        } catch (RuntimeException e) {
            metrics().judgeSkipped("error");
            log.warn("JudgeService#review#评审失败 run={} err={}", row.getId(), e.toString());
        }
    }

    /** 有 judge 模型才算"评审可用"，供上层决定要不要抽样 */
    public boolean available() {
        return properties.isJudgeEnabled()
                && properties.getJudgeModel() != null
                && !properties.getJudgeModel().isBlank();
    }

    // ---------------- 内部 ----------------

    private String buildPrompt(RunRow row, List<ToolCallRow> tools) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("用户原话：").append(nz(row.getInputPreview())).append('\n');
        sb.append("助手回复：").append(nz(row.getOutputPreview())).append('\n');
        sb.append("工具调用：");
        if (tools == null || tools.isEmpty()) {
            sb.append("无");
        } else {
            int budget = Math.max(properties.getJudgeEvidenceChars(), 200);
            for (ToolCallRow tool : tools) {
                String item = "%s(%s,rows=%s) ".formatted(tool.getToolName(), tool.getStatus(),
                        tool.getResultRows() == null ? "-" : tool.getResultRows());
                if (sb.length() + item.length() > budget + 200) {
                    sb.append("…");
                    break;
                }
                sb.append(item);
            }
        }
        sb.append("""
                
                请只输出这个 JSON：
                {"outcome":"answered|clarified|refused|failed","clarify_ok":0或1,"faithful":0或1,"instruction":0或1,"complete":0或1,"goal":0或1,"hallucinated":0或1,"reason":"20字以内"}
                
                第一步，判 outcome（它决定后面怎么算分）：
                - answered：直接把用户要的答案给了（具体数据、明细、统计、写操作结果）
                - clarified：信息不足或条件对不上，助手指明缺什么并反问。
                  例：用户问「上个月 A 类有多少」，工具结果里没有 A 类，助手回
                  「没有 A 类，现有 B、C 两类，你是想查 B 吗」
                - refused：请求超出助手能力（订票、发邮件、改密码、查他人的数据），如实说明做不到
                - failed：该答没答、答错、答非所问，或只把工具的报错复述了一遍
                
                第二步，按 outcome 分别判这几个维度：
                - faithful：数字和结论能否被工具结果支撑，凭空编造记 0。所有形态都要判。
                - instruction：是否在回应用户问的那件事（问 A 答 B 记 0）。所有形态都要判。
                - complete：answered = 该给的明细/数据是否给全；clarified/refused = 是否说清了
                  「为什么」和「下一步怎么走」（候选项、缺哪个参数、替代做法）；failed = 0
                - goal：answered = 用户的目标是否真的达成（写操作以工具是否成功为准）；
                  clarified/refused = 这一轮该完成的事（把问题推进到可执行、如实说明）是否完成；failed = 0
                - clarify_ok：只在 clarified/refused 时有意义。1 = 合理且有用；0 = 该自己查清的
                  却把活推回给用户（工具已返回可用数据仍反问、同一件事反复追问、拒答了本来能做到的事）
                - hallucinated：工具失败或返回 0 条，回复却说「已办好/没有数据」，记 1
                
                判定要求：
                - 先判 outcome 再打分。不要把反问默认当成没回答：用户信息真的不够时，反问才是正确做法。
                - 也不要放过偷懒的反问：用户已经说清楚、工具也查到了，还回头问用户，clarify_ok 记 0。
                - 语气礼貌、排版好看不加分。
                """);
        return sb.toString();
    }

    private void persist(RunRow row, JudgeVerdict verdict) {
        String judge = properties.getJudgeModel();
        List<EvalScoreRow> scores = new ArrayList<>(5);
        scores.add(judgeScore(row, "faithfulness", verdict.faithful(), judge, verdict));
        scores.add(judgeScore(row, "instruction", verdict.instruction(), judge, verdict));
        scores.add(judgeScore(row, "completeness", verdict.complete(), judge, verdict));
        scores.add(judgeScore(row, "goal", verdict.goal(), judge, verdict));
        // 澄清/拒答单独记一行：它不进四维的平均，否则"这次反问该不该"会被稀释成小数点后两位，
        // 而它恰恰是最需要盯住的比值（合理澄清率掉了 = 模型开始乱问）
        String interaction = verdict.interactionLabel();
        if (interaction != null) {
            scores.add(interactionScore(row, interaction, verdict, judge));
            // 同时打一条计数器：看板要的是「合理澄清率」这个比值，不能只躺在 mysql 里等人工查
            metrics().evalScore("interaction_quality", interaction);
        }
        mapper.insertScores(scores);
        metrics().evalScore("judge", verdict.metricLabel());
        warnIfDisagree(row, verdict);
    }

    /**
     * 规则层与评审层对同一条样本的判断不一致时计数。
     *
     * <p>只在「工具已经要求澄清、评审却说没回答」这个方向上计——反方向（评审判澄清、
     * 规则判 OK）多半是助手没调工具就自行反问了，属于正常发挥，不值得刷指标。
     * 这个计数器是 rubric 的体检指标：它一直涨，说明两套口径又分叉了。</p>
     */
    private void warnIfDisagree(RunRow row, JudgeVerdict verdict) {
        if (row.getNeedClarify() == null || row.getNeedClarify() != 1 || verdict.outcome().interaction()) {
            return;
        }
        String side = verdict.outcome().name().toLowerCase(Locale.ROOT);
        metrics().evalScore("judge_disagreement", "rule_clarify_judge_" + side);
        log.info("JudgeService#review#口径分歧 run={} 规则=CLARIFY 评审={} reason={}",
                row.getId(), verdict.outcome(), verdict.reason());
    }

    private EvalScoreRow judgeScore(RunRow row, String metric, int value, String judge, JudgeVerdict verdict) {
        return score(row, metric, value, value == 1 ? "PASS" : "FAIL", judge, detail(verdict, null));
    }

    private EvalScoreRow interactionScore(RunRow row, String label, JudgeVerdict verdict, String judge) {
        return score(row, "interaction_quality", verdict.clarifyOk(), label, judge, detail(verdict, null));
    }

    private EvalScoreRow score(RunRow row, String metric, int value, String label,
                               String judge, String detailJson) {
        EvalScoreRow score = new EvalScoreRow();
        score.setId(IdGen.next());
        score.setRunId(row.getId());
        score.setTraceId(row.getTraceId());
        score.setEvalSet("ONLINE");
        score.setMetric(metric);
        score.setScore(value == 1 ? BigDecimal.ONE : BigDecimal.ZERO);
        score.setLabel(label);
        score.setJudgeModel(judge);
        score.setRubricVersion(properties.getRubricVersion());
        score.setDetailJson(detailJson);
        score.setCreatedAt(LocalDateTime.now());
        return score;
    }

    /**
     * 详情里必须带 outcome：只看 goal=0 分不清是"答错"还是"该反问"，
     * 而这两件事的处置完全不同（前者改提示词，后者改口径或补分类）。
     */
    private static String detail(JudgeVerdict verdict, String extra) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"outcome\":\"").append(verdict.outcome().name());
        sb.append("\",\"clarify_ok\":").append(verdict.clarifyOk());
        if (extra != null) {
            sb.append(',').append(extra);
        }
        if (verdict.reason() != null && !verdict.reason().isBlank()) {
            sb.append(",\"reason\":\"").append(verdict.reason().replace("\"", "'").replace("\\", "/"))
                    .append('"');
        }
        return sb.append('}').toString();
    }

    /** 模型经常给 ```json 包裹，直接 parse 会失败——这不是模型错，是我们的容错该做的事 */
    private static String stripFence(String content) {
        if (content == null) {
            return "";
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start + 1, end);
            }
        }
        return text.trim();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private AiMetrics metrics() {
        return ObsHub.metrics();
    }
}
