package com.beastmouth.agentkit.model;

/**
 * 模型一次返回的结果。
 *
 * @param content      文本内容（JSON 协议下是可解析的 JSON 串）
 * @param promptTokens 输入 token 数，用于成本观测，未知时为 0
 * @param outputTokens 输出 token 数
 * @param cachedTokens 输入中命中前缀缓存的 token 数，未命中或网关未返回时为 0
 * @param elapsedMillis 本次调用耗时
 */
public record ModelReply(String content, int promptTokens, int outputTokens, int cachedTokens,
                         long elapsedMillis) {

    /**
     * 兼容构造：不需要区分命中/未命中的调用点（测试桩、Ollama 的流式分支等）用它。
     *
     * <p>故意保留而不是让所有调用点都写 5 个参数：命中数是「有就记、没有就 0」的观测字段，
     * 不该强迫每个只需返回一段文本的地方都显式声明它。</p>
     */
    public ModelReply(String content, int promptTokens, int outputTokens, long elapsedMillis) {
        this(content, promptTokens, outputTokens, 0, elapsedMillis);
    }

    /** 输入命中率，0~1；没有输入 token 时返回 0（避免除零，也避免报表出现 NaN） */
    public double cacheHitRatio() {
        return promptTokens <= 0 ? 0D : (double) cachedTokens / promptTokens;
    }
}
