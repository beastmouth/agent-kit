package com.beastmouth.agentkit.provider;

import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.obs.ObservedModelCall;
import com.beastmouth.agentkit.support.AgentKitException;
import com.beastmouth.agentkit.support.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供方注册表：收集容器里所有 {@link ModelProvider}，按路由键解析出这次该用哪个渠道。
 *
 * <p>新增提供方只需写一个 {@code @Component implements ModelProvider}，这里自动收录，
 * 不出现 if/else 分支，也不需要在配置文件里预先声明类名。</p>
 *
 * <h3>为什么先出 {@link RoutePlan} 再出 {@link ModelProvider}</h3>
 * <p>两套引擎的渠道组合方式完全不同：legacy 用 {@link FailoverModelProvider} 包一层
 * {@link ModelProvider}，AgentScope 用自己那套 {@code Model} 客户端。如果把「路由键 →
 * 哪个渠道 + 哪个兜底」的判断各写一份，两边迟早会不一致——同样的配置在 legacy 生效、
 * 切到 AgentScope 就失效，是最难查的一类问题。</p>
 * <p>所以解析只做一次：{@link #plan(String)} 产出纯数据（渠道 id 而已），legacy 拿它包
 * {@code FailoverModelProvider}，AgentScope 拿它建 {@code ResilientModel}。
 * 引擎换不换，路由语义都一样。</p>
 */
@Slf4j
@Component
public class ModelProviderRegistry {

    private final Map<String, ModelProvider> providers = new LinkedHashMap<>();
    private final AiProperties properties;
    private final ObjectProvider<ObservedModelCall> observers;

    /**
     * 主备组合实例缓存，键是 {@code 首选->兜底}。
     *
     * <p>熔断状态（冷却期）活在 {@link FailoverModelProvider} 实例字段上，每次调用都 new
     * 的话冷却窗口永远为零，等于没有熔断——所以按组合缓存，配置变了自然换一个键。
     * 按「组合」而不是按「路由键」缓存：两条路由配成同一组合时共用一个熔断器是合理的，
     * 它们打的是同一个上游。</p>
     */
    private final Map<String, FailoverModelProvider> failoverCache = new ConcurrentHashMap<>();

    /** 未知路由键只在第一次出现时告警，避免一句错配置刷满日志 */
    private final java.util.Set<String> warnedKeys = ConcurrentHashMap.newKeySet();

    public ModelProviderRegistry(List<ModelProvider> beans, AiProperties properties,
                                 ObjectProvider<ObservedModelCall> observers) {
        this.properties = properties;
        this.observers = observers;
        beans.forEach(provider -> providers.put(provider.id(), provider));
        log.info("ModelProviderRegistry#init#已注册提供方={} 默认={} 路由={}",
                providers.keySet(), properties.getProvider(),
                properties.getRoutes() == null ? List.of() : properties.getRoutes().keySet());
        checkRoutes(properties);
    }

    /**
     * 启动期体检：路由表里引用了不存在的渠道时，现在就在日志里说清楚。
     *
     * <p>不在这里抛异常——配置可能是热更或部分灰度，一处笔误不该让整个应用起不来；
     * 但等到第一句对话才炸、且只吐一个 {@code MODEL_UNAVAILABLE}，排查成本又是另一回事。
     * 所以启动期 error 一条带「可用渠道清单」的日志，运行期再抛可读错误。</p>
     */
    private void checkRoutes(AiProperties props) {
        if (props.getRoutes() == null || props.getRoutes().isEmpty()) {
            return;
        }
        props.getRoutes().forEach((key, route) -> {
            if (route == null) {
                log.error("ModelProviderRegistry#init#路由 {} 是空配置，命中时会回落默认渠道", key);
                return;
            }
            if (StringUtils.hasText(route.getProvider()) && !providers.containsKey(route.getProvider().trim())) {
                log.error("ModelProviderRegistry#init#路由 {} 的首选渠道不存在 provider={} 可用={}",
                        key, route.getProvider(), providers.keySet());
            }
            if (StringUtils.hasText(route.getFallbackProvider())
                    && !providers.containsKey(route.getFallbackProvider().trim())) {
                log.warn("ModelProviderRegistry#init#路由 {} 的兜底渠道不存在 provider={} 命中时只用首选",
                        key, route.getFallbackProvider());
            }
        });
        String intent = props.getIntentProvider();
        if (StringUtils.hasText(intent) && !providers.containsKey(intent.trim())) {
            log.error("ModelProviderRegistry#init#intent-provider 不存在 provider={} 可用={}，"
                    + "意图路由会回落本次请求的路由", intent, providers.keySet());
        }
    }

    /**
     * 路由键 → 渠道组合（纯数据，不建任何客户端）。
     *
     * @param routeKey 路由键；{@code null} / 空白表示走默认渠道
     *                 （{@code provider} + {@code fallback-provider}）
     */
    public RoutePlan plan(String routeKey) {
        String key = routeKey == null ? "" : routeKey.trim();
        if (key.isEmpty()) {
            return new RoutePlan(null, properties.getProvider(), properties.getFallbackProvider());
        }
        AiProperties.Route route = properties.getRoutes() == null ? null : properties.getRoutes().get(key);
        if (route == null) {
            if (warnedKeys.add(key)) {
                log.warn("ModelProviderRegistry#plan#未配置的路由键 key={} 已配置={}，本次回落默认渠道 {}",
                        key, properties.getRoutes() == null ? List.of() : properties.getRoutes().keySet(),
                        properties.getProvider());
            }
            return new RoutePlan(null, properties.getProvider(), properties.getFallbackProvider());
        }
        String primary = StringUtils.hasText(route.getProvider())
                ? route.getProvider().trim() : properties.getProvider();
        // 兜底刻意不继承全局：见 AiProperties#routes 的说明
        String fallback = StringUtils.hasText(route.getFallbackProvider())
                ? route.getFallbackProvider().trim() : "";
        return new RoutePlan(key, primary, fallback);
    }

    /**
     * 按路由键取可调用的提供方（配了兜底时返回主备组合）。
     *
     * <p>首选渠道不存在直接抛 {@code MODEL_UNAVAILABLE}：路由是显式配置的意图，
     * 静默换成别的渠道等于让配置失效，比报错更危险。兜底渠道不存在只 warn 并退回首选。</p>
     */
    public ModelProvider route(String routeKey) {
        RoutePlan plan = plan(routeKey);
        ModelProvider primary = providers.get(plan.primary());
        if (primary == null) {
            log.error("ModelProviderRegistry#route#路由 {} 的首选渠道不存在 provider={} 可用={}",
                    plan.key(), plan.primary(), providers.keySet());
            throw AgentKitException.of(ErrorCode.MODEL_UNAVAILABLE);
        }
        if (!plan.hasFallback()) {
            return primary;
        }
        ModelProvider fallback = providers.get(plan.fallback());
        if (fallback == null) {
            log.warn("ModelProviderRegistry#route#路由 {} 的兜底渠道不存在 id={} 可用={}，只用首选",
                    plan.key(), plan.fallback(), providers.keySet());
            return primary;
        }
        String pair = plan.cacheKey();
        return failoverCache.computeIfAbsent(pair, ignored -> {
            long cooldownMillis = properties.getFailoverCooldownSeconds() * 1000L;
            log.info("ModelProviderRegistry#route#主备组合就绪 pair={} 冷却={}s 观测={}",
                    pair, cooldownMillis / 1000, observers.getIfAvailable() != null);
            return new FailoverModelProvider(primary, fallback, observers.getIfAvailable(), cooldownMillis);
        });
    }

    /** 默认渠道（未命中任何路由时使用），等价于 {@code route(null)} */
    public ModelProvider current() {
        return route(null);
    }

    /**
     * 按 id 取单个提供方（不带主备组合），给提炼/自检这类后台任务定点用渠道。
     *
     * <p>与 {@link #route(String)} 的区别：后台任务明确指定了渠道（如本地 ollama），
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

    /** 默认渠道的说明文本，用于健康检查与前端提示 */
    public String description() {
        return plan(null).description();
    }

    /** 某条路由的说明文本，用于健康检查与前端提示 */
    public String description(String routeKey) {
        return plan(routeKey).description();
    }

    /** 已注册的提供方 id，用于健康检查与前端提示 */
    public List<String> availableIds() {
        return List.copyOf(providers.keySet());
    }

    /** 已配置的路由键，用于健康检查与前端提示 */
    public List<String> routeKeys() {
        return properties.getRoutes() == null ? List.of() : List.copyOf(properties.getRoutes().keySet());
    }

    /**
     * 一次路由解析的结果：只有渠道 id，没有客户端实例。
     *
     * @param key      命中的路由键；{@code null} 表示走默认渠道（未命中或本来就没传）
     * @param primary  首选渠道 id
     * @param fallback 兜底渠道 id，可能为 {@code null} / 空白，表示不兜底
     */
    public record RoutePlan(String key, String primary, String fallback) {

        public RoutePlan {
            fallback = StringUtils.hasText(fallback) ? fallback.trim() : null;
        }

        public boolean hasFallback() {
            return fallback != null && !fallback.equals(primary);
        }

        /** 主备组合的缓存键，也是日志里最好认的那个标识 */
        public String cacheKey() {
            return hasFallback() ? primary + "->" + fallback : primary;
        }

        /** 健康检查 / 前端提示用的可读文本，例如 {@code finance: siliconflow→deepseek} */
        public String description() {
            return key == null ? cacheKey() : key + ": " + cacheKey();
        }
    }
}
