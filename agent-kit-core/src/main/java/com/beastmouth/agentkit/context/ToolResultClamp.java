package com.beastmouth.agentkit.context;

/**
 * 工具结果截断。
 *
 * <p>为什么需要：工具契约里 {@code summary} 是「一句话结果」，但实际项目里总有人把
 * 整张明细表塞进来。一条 5 万字符的工具结果会把上下文预算一次吃掉，
 * 后面的推理全都在还这笔债。</p>
 *
 * <p><b>为什么只截 summary、不截 data</b>：{@code data} 是结构化结果，进的是框架 metadata，
 * 不进模型上下文；截它没有收益，只会让观测里查到半截 JSON。</p>
 *
 * <p><b>为什么保留首尾而不是只保留开头</b>：结论往往在末尾（「共 500 条，合计 …」），
 * 只留开头会让模型看到一个没有结论的清单，比不给更糟。</p>
 */
public final class ToolResultClamp {

    /** 尾部保留比例：结论通常在最后，留 1/4 比留 1/5 更稳 */
    private static final int TAIL_RATIO = 4;

    private ToolResultClamp() {
    }

    /**
     * 按上限截断，保留首尾。
     *
     * @param text     原始文本
     * @param maxChars 上限；{@code <=0} 表示不截断
     * @param toolName 工具名，只用于占位符里的定位信息
     * @return 原文（未超限）或「首段 + 占位说明 + 尾段」
     */
    public static String clamp(String text, int maxChars, String toolName) {
        if (maxChars <= 0 || text == null || text.length() <= maxChars) {
            return text;
        }
        int tail = Math.max(200, maxChars / TAIL_RATIO);
        int head = Math.max(200, maxChars - tail);
        // 两侧加起来可能超上限（head/tail 都有 200 下限），这是有意的：
        // 上限设得极小时，宁可多留一点可读内容，也不要把结果截成两句残缺的话
        String name = toolName == null || toolName.isBlank() ? "工具" : toolName;
        return text.substring(0, head)
                + "\n...[%s 结果过长已截断：共 %d 字符，省略 %d 字符，完整内容见本次工具调用明细]...\n"
                .formatted(name, text.length(), text.length() - head - tail)
                + text.substring(text.length() - tail);
    }
}
