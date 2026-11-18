package com.beastmouth.agentkit.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 进程内摘要缓存（默认实现）。
 *
 * <p><b>只适合单副本</b>：多副本下同一会话漂到另一个 Pod 会重新生成摘要，
 * 而新摘要的文字与旧的不同 → 前缀缓存整段失效。多副本请配
 * {@code agent-kit.context.compaction.summary-store=redis}。</p>
 *
 * <p>用 LRU 而不是无界 Map：摘要正文一条几百字，无界会随会话数线性涨。
 * 条数上限给个默认值即可——摘要的复用窗口是「同一个会话的连续几轮」，不是跨天。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "agent-kit.context.compaction", name = "summary-store",
        havingValue = "memory", matchIfMissing = true)
public class InMemorySummaryStore implements SummaryStore {

    private final int maxEntries;
    private final Map<String, String> cache;

    public InMemorySummaryStore(CompactionProperties properties) {
        this.maxEntries = Math.max(16, properties.getSummaryCacheMax());
        this.cache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maxEntries;
            }
        };
    }

    @Override
    public String id() {
        return "memory";
    }

    @Override
    public synchronized String get(String scope, String coveredKey) {
        if (coveredKey == null) {
            return null;
        }
        return cache.get(key(scope, coveredKey));
    }

    @Override
    public synchronized void put(String scope, String coveredKey, String summary) {
        if (coveredKey == null || summary == null || summary.isBlank()) {
            return;
        }
        cache.put(key(scope, coveredKey), summary);
    }

    private String key(String scope, String coveredKey) {
        return (scope == null ? "_" : scope) + '\u0000' + coveredKey;
    }
}
