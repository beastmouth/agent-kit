package com.beastmouth.agentkit.context;

import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.model.ModelReply;
import com.beastmouth.agentkit.provider.ModelProvider;
import com.beastmouth.agentkit.provider.ModelProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认压缩实现：LLM 摘要 + 前缀指纹复用。
 *
 * <h3>核心设计：摘要按前缀指纹冻结</h3>
 * <p>前缀缓存（DeepSeek 等按前缀完全匹配计费，命中价约为未命中的 1/50）要求
 * <b>前缀逐字一致</b>。如果每轮都重新生成摘要，摘要文字一变，它后面的全部内容作废，
 * 长会话会形成「越压越贵」的反向螺旋。</p>
 *
 * <p>所以摘要的版本号不是时间、不是轮次，而是<b>被压缩前缀的内容指纹</b>：
 * 前缀内容没变 → 指纹没变 → 直接复用缓存里的摘要正文（零 LLM 调用、逐字一致）；
 * 前缀多了一条 → 指纹变了 → 重新生成一次（此时前缀本来就要变，一次性失效是必要成本）。</p>
 *
 * <p>用内容指纹而不是消息 ID 的原因：消息 ID 是雪花、不连续，无法从「窗口里第 k 条」
 * 反推；而内容指纹与「前缀是否完全一致」本来就是同一个判定标准，正好对齐。</p>
 *
 * <h3>失败一律降级</h3>
 * <p>摘要模型不可用、缓存读不到、前缀算不出来——一律返回 passthrough（不压缩）。
 * 压缩是优化，不是正确性依赖；因为摘要服务挂了就让用户无法提问，是不可接受的失败方式。</p>
 */
@Slf4j
@Component
public class LlmContextCompactor implements ContextCompactor {

    /** 摘要消息的前缀标记。用 user 角色：两套引擎都会丢弃历史里的 system 消息 */
    static final String SUMMARY_MARK =
            "[以下是本会话前序对话的摘要，用于接续上下文；具体细节以最近的消息为准]\n";

    private static final String SYSTEM_PROMPT = """
            你是一个对话摘要器。把用户提供的历史对话压缩成一段结构化摘要，供后续对话继续使用。

            要求：
            1. 只保留对后续对话有用的事实：用户目标、已确认的数字与口径、已执行的动作及其真实结果、未完成事项。
            2. 不评价、不推测、不补充历史里没有的内容。
            3. 历史里助手声称「已完成」但没有任何工具执行痕迹的，写成「助手声称已完成（未见工具执行记录）」，
               不要直接写成「已完成」。假回执一旦被压进摘要，后面每一轮都会读到它。
            4. 输出纯文本，不要 Markdown 标题，不要分点编号，控制在 %d 字以内。
            5. 用中文输出。
            """;

    private final CompactionProperties properties;
    /**
     * 摘要存储。用 {@link ObjectProvider} 而不是直接注入，是为了「一个都没配也能启动」：
     * 存储实现放在可选模块（memory）里，缺模块时压缩退化为「每轮重新生成摘要」，
     * 而不是启动失败。这与 {@code AgentSessionStore} 对缓存的取舍一致。
     */
    private final SummaryStore summaryStore;
    /**
     * 允许为 null（单测 / 没有模型渠道的精简部署）。为 null 时压缩直接降级为不压缩，
     * 而不是启动失败——压缩不该成为启动的硬依赖。
     */
    private final ModelProviderRegistry registry;

    @Autowired
    public LlmContextCompactor(CompactionProperties properties,
                               ObjectProvider<SummaryStore> summaryStoreProvider,
                               ModelProviderRegistry registry) {
        this(properties, summaryStoreProvider.getIfAvailable(), registry);
    }

    /** 单测与手工装配用：直接给一个存储实例（可为 null） */
    LlmContextCompactor(CompactionProperties properties, SummaryStore summaryStore,
                        ModelProviderRegistry registry) {
        this.properties = properties;
        this.summaryStore = summaryStore;
        this.registry = registry;
        log.info("LlmContextCompactor#init#上下文压缩 enabled={} triggerTokens={} keepMessages={} summaryStore={}",
                properties.isEnabled(), properties.getTriggerTokens(), properties.getKeepMessages(),
                summaryStore == null ? "none" : summaryStore.id());
    }

    @Override
    public String id() {
        return "llm";
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled();
    }

    @Override
    public boolean retryOnOverflow() {
        return properties.isEnabled() && properties.isOverflowRetry();
    }

    @Override
    public CompactOutcome compact(CompactRequest request) {
        if (request == null) {
            return CompactOutcome.passthrough(List.of());
        }
        List<ChatMessage> history = request.history();
        try {
            // 引擎层已经先问过 enabled（为了零开销），这里再判一次：
            // 压缩器是对外暴露的组件，可能被业务代码直接调用，不能指望调用方记得先问
            if (!enabled()) {
                return CompactOutcome.passthrough(history);
            }
            int keep = Math.max(2, properties.getKeepMessages());
            if (history.size() <= keep) {
                // 没得压：保留窗口比历史还长。强制模式下也一样，硬压会把上下文清空
                return CompactOutcome.passthrough(history);
            }
            if (!request.force() && !overTrigger(history)) {
                return CompactOutcome.passthrough(history);
            }
            if (request.force()) {
                // 溢出兜底：模型已经报错了，保留窗口减半，确保这次一定能压下来
                keep = Math.max(2, keep / 2);
            }

            int coveredCount = history.size() - Math.min(keep, history.size() - 1);
            List<ChatMessage> prefix = history.subList(0, coveredCount);
            List<ChatMessage> tail = history.subList(coveredCount, history.size());

            String coveredKey = fingerprint(prefix);
            String summary = readSummary(request, coveredKey);
            boolean reused = summary != null && !summary.isBlank();
            if (!reused) {
                summary = generate(request, prefix);
                if (summary == null || summary.isBlank()) {
                    return CompactOutcome.passthrough(history);
                }
                writeSummary(request, coveredKey, summary);
            }

            List<ChatMessage> compacted = new ArrayList<>(tail.size() + 1);
            compacted.add(ChatMessage.user(SUMMARY_MARK + summary));
            compacted.addAll(tail);

            int savedChars = chars(prefix) - summary.length();
            log.info("ContextCompactor#compact#压缩完成 scope={} 原条数={} 压缩条数={} 保留={} reused={} 省字符={}",
                    request.scope(), history.size(), coveredCount, tail.size(), reused, savedChars);
            return new CompactOutcome(List.copyOf(compacted), summary, coveredKey,
                    coveredCount, reused, Math.max(0, savedChars));
        } catch (RuntimeException e) {
            // 压缩失败必须让对话继续：宁可多花 token，也不能让用户提问失败
            log.warn("ContextCompactor#compact#压缩失败，降级为不压缩 scope={} err={}",
                    request == null ? null : request.scope(), e.toString());
            return CompactOutcome.passthrough(history);
        }
    }

    /** 阈值判定：token 或条数任一超限即压缩；force 模式不走这里 */
    private boolean overTrigger(List<ChatMessage> history) {
        if (properties.getTriggerMessages() > 0 && history.size() > properties.getTriggerMessages()) {
            return true;
        }
        return estimateTokens(history) > properties.getTriggerTokens();
    }

    private int estimateTokens(List<ChatMessage> history) {
        double ratio = properties.getCharsPerToken() <= 0 ? 1.7D : properties.getCharsPerToken();
        return (int) Math.ceil(chars(history) / ratio);
    }

    private int chars(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            if (message != null && message.content() != null) {
                total += message.content().length();
            }
        }
        return total;
    }

    /**
     * 前缀内容指纹：SHA-256 取前 16 位十六进制。
     *
     * <p>用 {@code role + NUL + content} 逐条拼再接分隔符：只拼 content 会让
     * 「用户问 A、助手答 B」和「用户问 B、助手答 A」算出同一个指纹。</p>
     */
    private String fingerprint(List<ChatMessage> prefix) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ChatMessage message : prefix) {
                if (message == null) {
                    continue;
                }
                digest.update((message.role() == null ? "" : message.role()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update((message.content() == null ? "" : message.content()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 1);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // 理论上不可能：SHA-256 是 JDK 必备算法。真发生了也不能让对话失败
            log.warn("ContextCompactor#fingerprint#SHA-256 不可用，本次不压缩 err={}", e.toString());
            return null;
        }
    }

    private String readSummary(CompactRequest request, String coveredKey) {
        if (summaryStore == null || coveredKey == null) {
            return null;
        }
        try {
            return summaryStore.get(request.scope(), coveredKey);
        } catch (RuntimeException e) {
            log.warn("ContextCompactor#readSummary#读摘要失败按未命中处理 scope={} err={}",
                    request.scope(), e.toString());
            return null;
        }
    }

    private void writeSummary(CompactRequest request, String coveredKey, String summary) {
        if (summaryStore == null || coveredKey == null) {
            return;
        }
        try {
            summaryStore.put(request.scope(), coveredKey, summary);
        } catch (RuntimeException e) {
            // 写失败只影响下一轮能否复用，不影响本轮正确性
            log.warn("ContextCompactor#writeSummary#写摘要失败 scope={} err={}",
                    request.scope(), e.toString());
        }
    }

    private String generate(CompactRequest request, List<ChatMessage> prefix) {
        ModelReply reply = callModel(buildPrompt(request, prefix));
        if (reply == null || !StringUtils.hasText(reply.content())) {
            return null;
        }
        String summary = reply.content().strip();
        int max = Math.max(200, properties.getMaxSummaryChars());
        return summary.length() <= max ? summary : summary.substring(0, max);
    }

    /**
     * 调摘要模型。抽成可覆盖方法是为了单测能直接注入桩，不必搭一整套 provider 装配。
     *
     * @return null 表示「没有可用渠道」，调用方据此降级
     */
    protected ModelReply callModel(List<ChatMessage> prompt) {
        ModelProvider provider = resolveProvider();
        if (provider == null) {
            return null;
        }
        return provider.chat(prompt, false);
    }

    private ModelProvider resolveProvider() {
        if (registry == null) {
            return null;
        }
        try {
            if (StringUtils.hasText(properties.getProvider())) {
                ModelProvider named = registry.byId(properties.getProvider());
                if (named != null) {
                    return named;
                }
                // 配错渠道不该让压缩静默失效，也不该让对话失败：退回默认 + 告警
                log.warn("ContextCompactor#resolveProvider#配置的摘要渠道不存在，退回默认渠道 provider={}",
                        properties.getProvider());
            }
            return registry.current();
        } catch (RuntimeException e) {
            log.warn("ContextCompactor#resolveProvider#解析摘要渠道失败，本次不压缩 err={}", e.toString());
            return null;
        }
    }

    private List<ChatMessage> buildPrompt(CompactRequest request, List<ChatMessage> prefix) {
        StringBuilder sb = new StringBuilder(prefix.size() * 64);
        for (ChatMessage message : prefix) {
            if (message == null || !StringUtils.hasText(message.content())) {
                continue;
            }
            sb.append('[').append(label(message.role())).append("] ")
                    .append(message.content().strip()).append('\n');
        }
        if (StringUtils.hasText(request.userMessage())) {
            sb.append("\n[用户当前这一轮说] ").append(request.userMessage().strip());
        }
        return List.of(
                ChatMessage.system(String.format(SYSTEM_PROMPT, Math.max(200, properties.getMaxSummaryChars()))),
                ChatMessage.user(sb.toString()));
    }

    private String label(String role) {
        if (role == null) {
            return "未知";
        }
        return switch (role) {
            case "user" -> "用户";
            case "assistant" -> "助手";
            case "system" -> "系统";
            default -> role;
        };
    }
}
