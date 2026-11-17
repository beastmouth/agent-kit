package com.beastmouth.agentkit.eval;

import com.beastmouth.agentkit.obs.RunRow;
import com.beastmouth.agentkit.obs.ToolCallRow;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性质量规则（P3 第一层）。
 *
 * <h3>为什么先做这一层，而不是直接上 LLM 评审</h3>
 * <p>方案 §3.3 的原则：<b>能给代码判断的绝不喂 judge</b>。工具名、返回条数、finish_reason
 * 都是字符串比对，0 成本、可复现、不会被 judge 的偏好带偏。这一层能覆盖八成"静默失败"，
 * 而静默失败恰恰是线上最难发现的（不抛异常、HTTP 200、用户也未必立刻察觉）。</p>
 *
 * <h3>只读、纯函数</h3>
 * <p>不碰数据库、不调模型、不改入参——只有这样才能被单测逐条钉住。
 * 需要落库的东西由 {@link QualityEvaluator} 负责。</p>
 */
public final class QualityRules {

    /** 回复里出现这些词 = 声称"事情办成了" */
    private static final String[] SUCCESS_CLAIMS = {
            "已记", "已添加", "已帮", "已经记", "已保存", "已删除", "已修改", "已更新",
            "成功", "记好了", "记下", "添加好", "搞定", "办好了", "处理完"
    };

    /** 回复里出现这些词 = 断言"没有数据" */
    private static final String[] EMPTY_CLAIMS = {
            "没有", "无记录", "查不到", "暂无", "没找到", "没有找到", "一笔都没有", "没有任何"
    };

    private QualityRules() {
    }

    /**
     * 判定一次任务。
     *
     * @param row      任务行（需带 outputText，缺失时退化为 outputPreview）
     * @param tools    本次任务的工具明细（可为空）
     * @param maxSteps 最大轮次；达到即视为"没收敛"
     */
    public static QualityResult evaluate(RunRow row, List<ToolCallRow> tools, int maxSteps) {
        if (row == null) {
            return QualityResult.of(QualityLabel.OK, "NONE", null);
        }
        String reply = replyOf(row);
        List<String> failedTools = new ArrayList<>();
        boolean hasOkTool = false;
        boolean hasZeroRows = false;
        if (tools != null) {
            for (ToolCallRow tool : tools) {
                if (tool == null) {
                    continue;
                }
                if ("OK".equalsIgnoreCase(tool.getStatus())) {
                    hasOkTool = true;
                } else {
                    failedTools.add(tool.getToolName());
                }
                if (tool.getResultRows() != null && tool.getResultRows() == 0) {
                    hasZeroRows = true;
                }
            }
        }

        String successClaim = firstHit(reply, SUCCESS_CLAIMS);
        String emptyClaim = firstHit(reply, EMPTY_CLAIMS);

        // R1 幻觉成功：工具全失败，回复却说办成了。红线，必须排第一位。
        if (!failedTools.isEmpty() && !hasOkTool && successClaim != null) {
            return QualityResult.of(QualityLabel.HALLUCINATION, "R1_HALLUCINATION",
                    "failed=" + String.join(",", failedTools) + " claim=" + successClaim);
        }

        // R2 协议解析失败：模型没按 JSON 协议输出，只能走兜底
        if (nz(row.getParseFail()) == 1) {
            return QualityResult.of(QualityLabel.PARSE_FAIL, "R2_PARSE_FAIL", "parse_fail=1");
        }

        // R3 空回复：用户会看到一片空白（思维链吃满 max tokens 时最常见）
        if (reply == null || reply.isBlank()) {
            return QualityResult.of(QualityLabel.EMPTY_REPLY, "R3_EMPTY_REPLY", "reply_blank");
        }

        // R4 超步：跑到上限还没收口，说明提示词或工具设计让模型绕圈
        if (maxSteps > 0 && nz(row.getStepCount()) >= maxSteps) {
            return QualityResult.of(QualityLabel.OVERSTEP, "R4_OVERSTEP",
                    "steps=" + nz(row.getStepCount()) + " max=" + maxSteps);
        }

        // R5 兜底话术：上游已经放弃这次任务
        if (nz(row.getFallbackReply()) == 1) {
            return QualityResult.of(QualityLabel.FALLBACK_REPLY, "R5_FALLBACK_REPLY", "fallback_reply=1");
        }

        // R6 工具失败但没谎称成功：至少诚实，算失败但不算事故
        if (!failedTools.isEmpty() && !hasOkTool) {
            return QualityResult.of(QualityLabel.TOOL_FAIL, "R6_TOOL_FAIL",
                    "failed=" + String.join(",", failedTools));
        }

        // R7 空结果断言：工具 0 条，回复说"没有"。可能是对的，也可能是筛选条件写错——
        //    所以标成可疑但记成功，交给抽样评审去看
        if (hasZeroRows && emptyClaim != null) {
            return QualityResult.of(QualityLabel.EMPTY_ASSERT, "R7_EMPTY_ASSERT", "claim=" + emptyClaim);
        }

        // R8 降级：主渠道挂了换备用，结果可能对但质量会掉，比例高就要查
        if (nz(row.getFallbackUsed()) == 1) {
            return QualityResult.of(QualityLabel.FALLBACK_USED, "R8_FALLBACK", "fallback_used=1");
        }

        if (nz(row.getNeedClarify()) == 1) {
            return QualityResult.of(QualityLabel.CLARIFY, "R9_CLARIFY", "need_clarify=1");
        }
        return QualityResult.of(QualityLabel.OK, "NONE", null);
    }

    /** 回复原文优先；没有原文时用预览兜底（预览只有 300 字，可能漏掉结尾的断言） */
    private static String replyOf(RunRow row) {
        String text = row.getOutputText();
        if (text == null || text.isBlank()) {
            text = row.getOutputPreview();
        }
        return text;
    }

    private static String firstHit(String text, String[] words) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (String word : words) {
            if (text.contains(word)) {
                return word;
            }
        }
        return null;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
