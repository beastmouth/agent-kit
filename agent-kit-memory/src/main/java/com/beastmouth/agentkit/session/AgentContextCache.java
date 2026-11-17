package com.beastmouth.agentkit.session;

import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.memory.support.KitKeys;
import com.beastmouth.agentkit.support.Json;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 上下文包缓存（L1，Redis）。
 *
 * <p>一次对话开始前要凑齐的「会话上下文」由三次数据库查询组成：会话归属校验、
 * 最近窗口消息、上一轮业务子助手名。这三样在一轮对话里几乎不变（只在 append 后变），
 * 所以打成一个 JSON 包存 Redis，命中即短路，miss 回源后由写路径统一回填。</p>
 *
 * <p>三条纪律：</p>
 * <ul>
 *   <li><b>命中只允许变快，不允许变错</b>：包的一致性由 {@code AgentSessionStore#append}
 *       的写穿 + lastMessageId 乐观校验保证，包落后一律 evict 回源，宁可慢一次；</li>
 *   <li><b>缓存挂了对话必须照常</b>：本类任何异常只打 warn 并按 miss 处理，绝不抛给上层；</li>
 *   <li><b>key 里带 namespace + owner</b>：他人拿会话 ID 探测时 key 对不上必然 miss，
 *       归属校验仍然在 MySQL 层收口，不因缓存而豁免。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentContextCache {

    private final RedissonClient redissonClient;
    private final AiProperties properties;

    /** 上下文包：历史窗口 + 最新消息 ID（乐观校验用）+ 最近一次业务子助手名 */
    @Data
    public static class ContextPack {

        /** 包覆盖到的最新一条消息 ID；append 时与库中最新 ID 比对，落后即 evict */
        private long lastMessageId;

        /** 最近一次真正处理业务的子 Agent 名（main 路由直答不算），无则 null */
        private String lastAgent;

        /** 历史窗口（时间正序，与 recentHistory 语义一致，最多 historyLimit 条） */
        private List<ChatMessage> history = new ArrayList<>();

        static ContextPack of(long lastMessageId, String lastAgent, List<ChatMessage> history) {
            ContextPack pack = new ContextPack();
            pack.setLastMessageId(lastMessageId);
            pack.setLastAgent(lastAgent);
            pack.setHistory(new ArrayList<>(history));
            return pack;
        }
    }

    /** 缓存是否启用。调用方先看这个再决定要不要走缓存路径 */
    public boolean enabled() {
        return properties.isContextCacheEnabled();
    }

    /** 读包。miss / 开关关闭 / Redis 异常一律返回 null，由调用方回源 */
    public ContextPack get(String namespace, String owner, long sessionId) {
        if (!enabled()) {
            return null;
        }
        try {
            String json = bucket(namespace, owner, sessionId).get();
            return Json.parseObject(json, ContextPack.class);
        } catch (RuntimeException e) {
            log.warn("AgentContextCache#get#读取失败按 miss 处理 namespace={} owner={} sessionId={}",
                    namespace, owner, sessionId, e);
            return null;
        }
    }

    /** 写包（回填或写穿）。失败只打 warn，不影响主流程 */
    public void put(String namespace, String owner, long sessionId, ContextPack pack) {
        if (!enabled()) {
            return;
        }
        try {
            bucket(namespace, owner, sessionId).set(Json.toJson(pack),
                    Duration.ofSeconds(Math.max(60, properties.getContextCacheTtlSeconds())));
        } catch (RuntimeException e) {
            log.warn("AgentContextCache#put#写缓存失败 namespace={} owner={} sessionId={}",
                    namespace, owner, sessionId, e);
        }
    }

    /** 删包。失败只打 warn */
    public void evict(String namespace, String owner, long sessionId) {
        if (!enabled()) {
            return;
        }
        try {
            bucket(namespace, owner, sessionId).delete();
        } catch (RuntimeException e) {
            log.warn("AgentContextCache#evict#删缓存失败 namespace={} owner={} sessionId={}",
                    namespace, owner, sessionId, e);
        }
    }

    /**
     * key 里带分区维度。
     *
     * <p>冗余的防护：{@code sessionId} 是雪花、全局唯一，理论上只靠它就不会串。
     * 但「理论唯一」依赖的是「会话 ID 不会被跨分区复用」这个假设，而分区是
     * 显式的隔离边界——把隔离建立在「ID 恰好不会撞」上，是那种平时没事、
     * 一旦出事就没法解释的设计。多两个字符串前缀，换一个不依赖假设的边界。</p>
     */
    private RBucket<String> bucket(String namespace, String owner, long sessionId) {
        return redissonClient.getBucket(KitKeys.agentContext(namespace, owner, sessionId), StringCodec.INSTANCE);
    }
}
