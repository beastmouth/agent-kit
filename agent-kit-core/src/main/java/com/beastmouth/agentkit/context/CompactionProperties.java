package com.beastmouth.agentkit.context;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上下文治理配置：压缩、摘要缓存、工具结果卸载。
 *
 * <p><b>默认全部关闭</b>（{@code enabled=false}、{@code unload-threshold-chars=0}）。
 * 理由：这些是「省钱与保命」的优化，不是正确性必需项。默认打开意味着任何一次升级
 * 都会悄悄改变线上对话的上下文构成，出问题时无法归因到具体版本。</p>
 *
 * <p>为什么单独一个前缀而不是塞进 {@code AiProperties}：这些开关只在「历史会变长」的
 * 场景才有意义，且两套引擎共用；混进模型配置会让「换了模型为什么行为变了」多一个怀疑方向。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent-kit.context.compaction")
public class CompactionProperties {

    /** 总开关。关闭时压缩器零开销跳过，行为与未引入压缩前完全一致 */
    private boolean enabled = false;

    /**
     * 触发压缩的估算 token 数。
     *
     * <p>估算用 {@link #charsPerToken} 折算，误差不影响正确性——压缩早晚一点都可以，
     * 压错了才会出事，而「压错了」由前缀指纹与摘要复用机制兜住。</p>
     */
    private int triggerTokens = 24000;

    /** 按消息条数触发；0 表示只看 token */
    private int triggerMessages = 0;

    /** 压缩后保留的尾部原文条数。太小会让模型失去最近语境，太大会让压缩白做 */
    private int keepMessages = 8;

    /** 摘要正文上限（字符）。超了说明该提前压，而不是继续往摘要里塞 */
    private int maxSummaryChars = 1200;

    /**
     * 生成摘要用的渠道 id（{@code agent-kit.endpoints} 里的键）。
     *
     * <p>留空则用默认渠道。摘要不需要主模型那么强，指到一个便宜渠道能省下可观的成本——
     * 但注意<b>不要指向一个比主模型更慢的渠道</b>：压缩发生在对话主链路上，慢渠道会把
     * 首字延迟拖长，用户能感觉到。</p>
     */
    private String provider = "";

    /** 字符 → token 的折算系数（中文为主的场景取 1.7 字/token） */
    private double charsPerToken = 1.7D;

    /**
     * 摘要缓存实现：{@code memory}（进程内 LRU）或 {@code redis}。
     *
     * <p><b>多副本部署必须用 redis</b>：内存实现下，同一会话漂到另一个 Pod 会重新生成摘要，
     * 而新摘要的文字与旧的不同 → 前缀缓存整段失效。单副本用 memory 就够，别多引一个依赖。</p>
     */
    private String summaryStore = "memory";

    /** 内存摘要缓存的最大条数（仅 {@code summary-store=memory} 时生效） */
    private int summaryCacheMax = 512;

    /**
     * Redis 摘要缓存的有效期（秒），仅 {@code summary-store=redis} 时生效。
     *
     * <p>默认 7 天：摘要只服务于「同一会话最近几轮」的复用，会话超过一周没动，
     * 旧摘要基本不会再被读到，留着只是占内存。</p>
     */
    private int summaryTtlSeconds = 604_800;

    /**
     * 单条工具结果超过多少字符时卸载。0 表示不卸载。
     *
     * <p>卸载后上下文只保留首尾预览与占位符，完整内容另存。默认关闭是因为它改变了
     * 模型能看到的信息量——有些业务就是要模型读完整明细，开了反而答不准。</p>
     */
    private int unloadThresholdChars = 0;

    /** 上下文溢出（{@code context_length_exceeded}）时是否强制压缩后重试一次 */
    private boolean overflowRetry = true;
}
