package com.beastmouth.agentkit.eval;

import com.beastmouth.agentkit.agent.AgentResult;
import com.beastmouth.agentkit.agent.ChatSink;
import com.beastmouth.agentkit.agent.ChatEngine;
import com.beastmouth.agentkit.eval.mapper.EvalMapper;
import com.beastmouth.agentkit.obs.IdGen;
import com.beastmouth.agentkit.obs.ObsHub;
import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.context.RunContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 离线黄金集回归（P3 第三层）。
 *
 * <h3>为什么用 profile + 开关双重限制</h3>
 * <p>它会<b>真的调模型、真的写数据库</b>（用的 {@code golden-user-id} 那个账号）。
 * 要是跟主流程一起启动，生产环境每次发版都会自动跑一遍，产出几十条假账单。
 * 所以：{@code --spring.profiles.active=golden} 且 {@code agent-kit.eval.golden-enabled=true}
 * 两个条件同时满足才会执行。</p>
 *
 * <h3>门禁取第 1 档：只卡"不降"</h3>
 * <p>评审决议第 5 条：首版只与上一批次比，不设绝对阈值。绝对阈值在冷启动时会把
 * 波动误判成退化，阻塞正常合并；等基线稳两周再谈绝对线。</p>
 *
 * <h3>pass^k 而不是"更多用例"</h3>
 * <p>同一批用例跑 {@code golden-repeat} 遍。单次 70% 的 Agent，跑 4 遍全过只有 24%——
 * 稳定性比覆盖率更能说明问题。</p>
 */
@Slf4j
@Component
@Profile("golden")
@ConditionalOnProperty(prefix = "agent-kit.eval", name = "golden-enabled", havingValue = "true")
@RequiredArgsConstructor
public class GoldenSetRunner implements ApplicationRunner {

    private static final DateTimeFormatter BATCH_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final EvalProperties properties;
    private final EvalMapper mapper;
    private final ChatEngine chatEngine;

    @Override
    public void run(ApplicationArguments args) {
        List<GoldenCaseRow> cases = mapper.selectGoldenCases();
        if (cases.isEmpty()) {
            log.warn("GoldenSetRunner#run#黄金集为空，跳过（先往 ai_golden_case 插用例）");
            return;
        }
        String subject = properties.getGoldenSubject();
        if (subject == null || subject.isBlank()) {
            log.error("GoldenSetRunner#run#未配置 agent-kit.eval.golden-subject，黄金集需要一个调用方标识才能跑");
            return;
        }

        // 批次号里带上引擎名：同一份黄金集要分别用 legacy / agentscope 跑一遍做对比，
        // 只靠时间戳区分两批，事后根本分不清哪批是哪套引擎跑的（改一次引擎就得翻日志）
        String batchId = "G" + chatEngine.engineName().toUpperCase(Locale.ROOT)
                + "-" + LocalDateTime.now().format(BATCH_FMT);
        int repeat = Math.max(1, properties.getGoldenRepeat());
        int total = 0;
        int passed = 0;
        int toolHit = 0;

        log.info("GoldenSetRunner#run#开始 batch={} cases={} repeat={} subject={}",
                batchId, cases.size(), repeat, subject);

        for (GoldenCaseRow goldenCase : cases) {
            for (int round = 1; round <= repeat; round++) {
                CaseResult result = runCase(goldenCase, subject);
                total++;
                if (result.passed()) {
                    passed++;
                }
                if (result.toolHit()) {
                    toolHit++;
                }
                record(batchId, goldenCase, result);
                // 失败原因必须进日志：只知道 FAIL 不知道哪一维失败，排查要重新跑一遍
                log.info("GoldenSetRunner#case#{} r{} tool={} pass={} rows={} why={} reply={}",
                        goldenCase.getName(), round, result.actualTools(),
                        result.passed() ? "PASS" : "FAIL", result.rowsSeen(),
                        result.failReason().isEmpty() ? "-" : result.failReason(),
                        abbreviate(result.reply(), 60));
            }
        }

        BigDecimal rate = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(passed).divide(BigDecimal.valueOf(total), 4, java.math.RoundingMode.HALF_UP);
        BigDecimal toolRate = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(toolHit).divide(BigDecimal.valueOf(total), 4, java.math.RoundingMode.HALF_UP);
        BigDecimal previous = mapper.selectPreviousGoldenAverage(batchId, GoldenRubric.VERSION);

        log.info("GoldenSetRunner#summary#batch={} rubric={} 任务成功率={} 工具命中率={} 样本={} 上批基线={}",
                batchId, GoldenRubric.VERSION, rate, toolRate, total, previous);

        gate(batchId, rate, previous);
    }

    // ---------------- 单条用例 ----------------

    private CaseResult runCase(GoldenCaseRow goldenCase, String subject) {
        StringBuilder reply = new StringBuilder();
        AgentResult result;
        // 黄金集是从 ApplicationRunner 直接进引擎的，没有入口帮忙塞 RunContext；
        // 工具侧若依赖调用方标识做分组/限流，不设就会拿到 null
        RunContext.set(RunContext.Subject.of(subject, Map.of("entryType", "GOLDEN")));
        try {
            result = chatEngine.handle(List.of(), goldenCase.getInput(), ChatSink.NOOP);
            if (result != null && result.reply() != null) {
                reply.append(result.reply());
            }
        } catch (RuntimeException e) {
            log.warn("GoldenSetRunner#runCase#执行异常 case={} err={}", goldenCase.getName(), e.toString());
            return new CaseResult(false, false, "-", null, "异常:" + e.getClass().getSimpleName(), "exception");
        } finally {
            RunContext.clear();
        }
        if (result == null) {
            return new CaseResult(false, false, "-", null, "", "no_result");
        }

        List<AgentResult.ToolEvent> events = result.events() == null ? List.of() : result.events();
        String actual = events.isEmpty() ? "-" : String.join(",", events.stream().map(AgentResult.ToolEvent::name).toList());

        GoldenRubric.Verdict verdict = GoldenRubric.judge(goldenCase, events, reply.toString());
        return new CaseResult(verdict.passed(), verdict.toolHit(), actual, rowsOf(events),
                reply.toString(), verdict.failReason());
    }

    // ---------------- 落库与门禁 ----------------

    private void record(String batchId, GoldenCaseRow goldenCase, CaseResult result) {
        try {
            mapper.insertScores(List.of(
                    goldenScore(batchId, goldenCase, "task_success", result.passed() ? 1 : 0, result, "GOLDEN"),
                    goldenScore(batchId, goldenCase, "tool_accuracy", result.toolHit() ? 1 : 0, result, "GOLDEN")));
            ObsHub.metrics().goldenCase(goldenCase.getLayer(), result.passed() ? "PASS" : "FAIL");
        } catch (RuntimeException e) {
            log.warn("GoldenSetRunner#record#评分落库失败 case={} err={}", goldenCase.getName(), e.toString());
        }
    }

    private EvalScoreRow goldenScore(String batchId, GoldenCaseRow goldenCase, String metric,
                                     int value, CaseResult result, String set) {
        EvalScoreRow score = new EvalScoreRow();
        score.setId(IdGen.next());
        score.setEvalSet(set);
        score.setMetric(metric);
        score.setScore(value == 1 ? BigDecimal.ONE : BigDecimal.ZERO);
        score.setLabel(value == 1 ? "PASS" : "FAIL");
        score.setRubricVersion(GoldenRubric.VERSION);
        score.setBatchId(batchId);
        score.setDetailJson("{\"case\":\"" + goldenCase.getName() + "\",\"layer\":\"" + goldenCase.getLayer()
                + "\",\"expected\":\"" + nz(goldenCase.getExpectedTool()) + "\",\"actual\":\""
                + nz(result.actualTools()) + "\",\"why\":\"" + nz(result.failReason()) + "\"}");
        score.setCreatedAt(LocalDateTime.now());
        return score;
    }

    private void gate(String batchId, BigDecimal rate, BigDecimal previous) {
        if (previous == null) {
            log.info("GoldenSetRunner#gate#没有历史基线，本次作为基线存档 batch={} rate={}", batchId, rate);
            return;
        }
        if (!"non-regression".equalsIgnoreCase(properties.getGoldenGate())) {
            log.info("GoldenSetRunner#gate#门禁已关闭（golden-gate=off），仅记录不阻塞");
            return;
        }
        BigDecimal threshold = previous.subtract(BigDecimal.valueOf(properties.getGoldenMaxDrop()));
        if (rate.compareTo(threshold) < 0) {
            // 抛异常让进程非 0 退出：CI 里这是最直接的失败信号，不用再解析日志
            throw new IllegalStateException(
                    "黄金集回归失败：本批成功率 %s 低于上一批 %s 超过 %.0f 个百分点（阈值 %s）"
                            .formatted(rate, previous, properties.getGoldenMaxDrop() * 100, threshold));
        }
        log.info("GoldenSetRunner#gate#通过 本批={} 基线={} 阈值={}", rate, previous, threshold);
    }

    private static Integer rowsOf(List<AgentResult.ToolEvent> events) {
        return events.stream().map(AgentResult.ToolEvent::rows).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** 单条用例的结果，字段少但每条都要能在日志里一眼看清 */
    private record CaseResult(boolean passed, boolean toolHit, String actualTools, Integer rowsSeen,
                              String reply, String failReason) {
    }
}
