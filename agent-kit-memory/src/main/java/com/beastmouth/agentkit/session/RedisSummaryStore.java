package com.beastmouth.agentkit.session;

import com.beastmouth.agentkit.context.CompactionProperties;
import com.beastmouth.agentkit.context.SummaryStore;
import com.beastmouth.agentkit.memory.support.KitKeys;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 摘要存储的 Redis 实现（多副本部署用）。
 *
 * <p>为什么必须能在 Redis 里：摘要复用是「前缀逐字一致」的前提。内存实现下，
 * 同一会话漂到另一个 Pod 会重新生成摘要，新摘要的文字与旧的不同，前缀缓存整段失效——
 * 这个损失比摘要本身贵得多。</p>
 *
 * <p>只有配了 {@code summary-store=redis} 才装配；否则退回 {@code InMemorySummaryStore}。
 * 两者用同一个配置键互斥，不会出现「两个 SummaryStore Bean」的歧义。</p>
 *
 * <p>读写失败一律按「未命中」处理：拿不到摘要就重新生成一份，代价是一次 LLM 调用，
 * 比让整轮对话失败便宜得多。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "agent-kit.context.compaction", name = "summary-store", havingValue = "redis")
public class RedisSummaryStore implements SummaryStore {

    private final RedissonClient redissonClient;
    private final CompactionProperties properties;

    public RedisSummaryStore(RedissonClient redissonClient, CompactionProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        log.info("RedisSummaryStore#init#上下文摘要存储 = redis ttl={}s", properties.getSummaryTtlSeconds());
    }

    @Override
    public String id() {
        return "redis";
    }

    @Override
    public String get(String scope, String coveredKey) {
        if (coveredKey == null) {
            return null;
        }
        try {
            return bucket(scope, coveredKey).get();
        } catch (RuntimeException e) {
            log.warn("RedisSummaryStore#get#读取失败按未命中处理 scope={} coveredKey={}",
                    scope, coveredKey, e);
            return null;
        }
    }

    @Override
    public void put(String scope, String coveredKey, String summary) {
        if (coveredKey == null || summary == null || summary.isBlank()) {
            return;
        }
        try {
            bucket(scope, coveredKey).set(summary,
                    Duration.ofSeconds(Math.max(60, properties.getSummaryTtlSeconds())));
        } catch (RuntimeException e) {
            log.warn("RedisSummaryStore#put#写入失败 scope={} coveredKey={}", scope, coveredKey, e);
        }
    }

    private RBucket<String> bucket(String scope, String coveredKey) {
        return redissonClient.getBucket(KitKeys.contextSummary(scope, coveredKey), StringCodec.INSTANCE);
    }
}
