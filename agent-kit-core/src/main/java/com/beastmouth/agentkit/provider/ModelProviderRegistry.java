package com.beastmouth.agentkit.provider;

import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.obs.ObservedModelCall;
import com.beastmouth.agentkit.support.AgentKitException;
import com.beastmouth.agentkit.support.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供方注册表：收集容器里所有 {@link ModelProvider}，按配置挑一个。
 *
 * <p>新增提供方只需写一个 {@code @Component implements ModelProvider}，这里自动收录，
 * 不出现 if/else 分支，也不需要在配置文件里预先声明类名。</p>
 */
@Slf4j
@Component
public class ModelProviderRegistry {

    private final Map<String, ModelProvider> providers = new LinkedHashMap<>();
    private final AiProperties properties;
    private final ObjectProvider<ObservedModelCall> observers;

    /**
     * 主备组合实例缓存。熔断状态（冷却期）活在 {@link FailoverModelProvider} 实例字段上，
     * 每次调用都 new 的话冷却窗口永远为零，等于没有熔断——按组合 id 缓存，配置变了才重建。
     */
    private volatile FailoverModelProvider cachedFailover;
    private volatile String cachedPair;

    public ModelProviderRegistry(List<ModelProvider> beans, AiProperties properties,
                                 ObjectProvider<ObservedModelCall> observers) {
        this.properties = properties;
        this.observers = observers;
        beans.forEach(provider -> providers.put(provider.id(), provider));
        log.info("ModelProviderRegistry#init#已注册提供方={} 当前={}", providers.keySet(), properties.getProvider());
    }

    /**
     * 按 id 取单个提供方（不带主备组合），给提炼/自检这类后台任务定点用渠道。
     *
     * <p>与 {@link #current()} 的区别：后台任务明确指定了渠道（如本地 ollama），
     * 渠道不存在时抛错由调用方降级，而不是静默换成主链路渠道——那样配置就形同虚设。</p>
     */
    public ModelProvider byId(String id) {
        ModelProvider provider = providers.get(id);
        if (provider == null) {
            log.error("ModelProviderRegistry#byId#未找到提供方 id={} 已注册={}", id, providers.keySet());
            throw AgentKitException.of(ErrorCode.MODEL_UNAVAILABLE);
        }
        return provider;
    }

    /** 当前生效的提供方（配了兜底时返回主备组合） */
    public ModelProvider current() {
        ModelProvider primary = providers.get(properties.getProvider());
        if (primary == null) {
            log.error("ModelProviderRegistry#current#未找到提供方 id={} 已注册={}", properties.getProvider(), providers.keySet());
            throw AgentKitException.of(ErrorCode.MODEL_UNAVAILABLE);
        }
        String fallbackId = properties.getFallbackProvider();
        if (fallbackId == null || fallbackId.isBlank() || fallbackId.equals(primary.id())) {
            return primary;
        }
        ModelProvider fallback = providers.get(fallbackId);
        if (fallback == null) {
            log.warn("ModelProviderRegistry#current#兜底提供方不存在 id={} 已注册={}，只用首选",
                    fallbackId, providers.keySet());
            return primary;
        }
        String pair = primary.id() + "->" + fallbackId;
        FailoverModelProvider failover = cachedFailover;
        if (failover == null || !pair.equals(cachedPair)) {
            long cooldownMillis = properties.getFailoverCooldownSeconds() * 1000L;
            failover = new FailoverModelProvider(primary, fallback, observers.getIfAvailable(), cooldownMillis);
            cachedFailover = failover;
            cachedPair = pair;
            log.info("ModelProviderRegistry#current#主备组合就绪 pair={} 冷却={}s 观测={}",
                    pair, cooldownMillis / 1000, observers.getIfAvailable() != null);
        }
        return failover;
    }

    /** 主备组合的说明文本，用于健康检查与前端提示 */
    public String description() {
        String fallbackId = properties.getFallbackProvider();
        if (fallbackId == null || fallbackId.isBlank()) {
            return properties.getProvider();
        }
        return properties.getProvider() + "→" + fallbackId;
    }

    /** 已注册的提供方 id，用于健康检查与前端提示 */
    public List<String> availableIds() {
        return List.copyOf(providers.keySet());
    }
}
