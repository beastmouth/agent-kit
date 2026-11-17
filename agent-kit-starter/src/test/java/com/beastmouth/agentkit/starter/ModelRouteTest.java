package com.beastmouth.agentkit.starter;

import com.beastmouth.agentkit.agent.Agent;
import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.context.RunContext;
import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.model.ModelReply;
import com.beastmouth.agentkit.obs.ObservedModelCall;
import com.beastmouth.agentkit.provider.ModelProvider;
import com.beastmouth.agentkit.provider.ModelProviderRegistry;
import com.beastmouth.agentkit.provider.ModelRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型路由的三层优先级与兜底语义。
 *
 * <p>这几条是「按业务分渠道」的全部行为约定，也是配错时最容易静默失效的地方：
 * 标签写错、路由没配、兜底被意外继承，三种情况都不会报错，只会让请求悄悄跑到别的渠道上
 * ——账单和合规问题都从这里开始。所以逐条钉住。</p>
 */
class ModelRouteTest {

    @AfterEach
    void clearContext() {
        RunContext.clear();
    }

    @Test
    @DisplayName("没有任何路由声明时走全局默认渠道与全局兜底")
    void defaultsToGlobalProvider() {
        ModelRouter router = router(props());
        assertEquals("cheap", router.forAgent(null).id());
        ModelProviderRegistry.RoutePlan plan = router.planFor(null);
        assertNull(plan.key(), "未命中路由时 key 必须是 null，日志与健康检查据此区分「默认」和「配了」");
        assertEquals("cheap", plan.primary());
        assertEquals("strong", plan.fallback());
    }

    @Test
    @DisplayName("Agent 自己声明路由时优先于全局默认")
    void agentDeclarationWins() {
        ModelRouter router = router(props());
        assertEquals("strong", router.forAgent(agent("finance")).id());
        assertEquals("finance", router.planFor(agent("finance")).key());
    }

    @Test
    @DisplayName("请求标签覆盖 Agent 声明（业务侧可以临时改渠道，不改代码不重启）")
    void requestLabelOverridesAgent() {
        ModelRouter router = router(props());
        try (AutoCloseable ignored = label("batch")) {
            assertEquals("embed", router.forAgent(agent("finance")).id());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("标签写了没配置的路由键：回落默认，不抛错")
    void unknownRouteKeyFallsBack() {
        ModelRouter router = router(props());
        try (AutoCloseable ignored = label("typo")) {
            ModelProviderRegistry.RoutePlan plan = router.planFor(null);
            assertNull(plan.key());
            assertEquals("cheap", plan.primary());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("路由内不写兜底就是不兜底，不继承全局兜底")
    void routeFallbackIsNotInherited() {
        ModelRouter router = router(props());
        ModelProviderRegistry.RoutePlan finance = router.planFor(agent("finance"));
        assertEquals("strong", finance.primary());
        assertFalse(finance.hasFallback(), "跨渠道静默降级会让业务跑到未经评估的模型上");
    }

    @Test
    @DisplayName("路由内显式写的兜底生效，且组合实例被缓存（熔断状态要跨请求存活）")
    void routeFallbackApplies() {
        ModelProviderRegistry registry = registry(props());
        ModelProvider first = registry.route("batch");
        ModelProvider second = registry.route("batch");
        assertEquals(first, second, "每次 new 一个的话熔断冷却窗口永远是零");
        assertEquals("embed->cheap", registry.plan("batch").cacheKey());
    }

    @Test
    @DisplayName("意图路由单独配了渠道就走它，没配则跟随请求标签")
    void intentProvider() {
        AiProperties props = props();
        props.setIntentProvider("strong");
        assertEquals("strong", new ModelRouter(registry(props), props).forIntent().id());

        ModelRouter follow = router(props());
        try (AutoCloseable ignored = label("batch")) {
            assertEquals("embed", follow.forIntent().id());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("流式开关：全局关掉即全关，渠道可以单独覆盖回开")
    void streamSwitch() {
        AiProperties props = props();
        assertTrue(props.streamOf("cheap"), "默认开");

        props.setStream(false);
        assertFalse(props.streamOf("cheap"));

        AiProperties.Endpoint endpoint = new AiProperties.Endpoint();
        endpoint.setStream(true);
        props.getEndpoints().put("cheap", endpoint);
        assertTrue(props.streamOf("cheap"), "渠道配置优先于全局");
        assertFalse(props.streamOf("strong"), "没单独配的渠道仍跟随全局");
    }

    @Test
    @DisplayName("endpoints 里出现没有内置实现的 id：自动注册为自定义 OpenAI 兼容渠道")
    void customProviderFromEndpoints() {
        AiProperties props = props();
        AiProperties.Endpoint custom = new AiProperties.Endpoint();
        custom.setBaseUrl("https://llm.internal.corp/v1");
        custom.setModel("qwen2.5-72b");
        custom.setApiKey("internal-key");
        props.getEndpoints().put("custom", custom);

        ModelProviderRegistry registry = registry(props);
        assertEquals("custom", registry.byId("custom").id(),
                "endpoints 里的未知键应长出同名渠道，零代码接入公司内网网关");
        assertTrue(registry.availableIds().containsAll(List.of("custom", "cheap", "strong", "embed")),
                "自定义渠道与内置渠道并存");
    }

    @Test
    @DisplayName("自定义渠道可以进 routes 参与业务分流")
    void customProviderServesRoutes() {
        AiProperties props = props();
        AiProperties.Endpoint custom = new AiProperties.Endpoint();
        custom.setBaseUrl("https://llm.internal.corp/v1");
        custom.setModel("qwen2.5-72b");
        props.getEndpoints().put("company-gateway", custom);

        AiProperties.Route finance = new AiProperties.Route();
        finance.setProvider("company-gateway");
        props.getRoutes().put("finance", finance);

        ModelProviderRegistry registry = registry(props);
        ModelProviderRegistry.RoutePlan plan = registry.plan("finance");
        assertEquals("company-gateway", plan.primary());
        assertEquals("company-gateway", registry.route("finance").id());
    }

    @Test
    @DisplayName("自定义渠道路由键写错：仍然回落默认并告警，不抛错")
    void customProviderUnknownKeyStillFallsBack() {
        AiProperties props = props();
        AiProperties.Endpoint custom = new AiProperties.Endpoint();
        custom.setBaseUrl("https://llm.internal.corp/v1");
        custom.setModel("qwen2.5-72b");
        props.getEndpoints().put("custom", custom);

        ModelProviderRegistry registry = registry(props);
        ModelProviderRegistry.RoutePlan plan = registry.plan("custmo");
        assertNull(plan.key());
        assertEquals("cheap", plan.primary());
    }

    @Test
    @DisplayName("endpoints 键与内置渠道同名：是给内置渠道填参数，不重复注册")
    void endpointForBuiltinIsNotReplaced() {
        AiProperties props = props();
        AiProperties.Endpoint cheap = new AiProperties.Endpoint();
        cheap.setBaseUrl("https://cheap.example.com/v1");
        props.getEndpoints().put("cheap", cheap);

        ModelProviderRegistry registry = registry(props);
        assertSame(Fake.class, registry.byId("cheap").getClass(),
                "代码里显式注册的 Bean 优先，配置键只是给它填端点参数");
    }

    // ---------------------------------------------------------------- 夹具

    private static AiProperties props() {
        AiProperties props = new AiProperties();
        props.setProvider("cheap");
        props.setFallbackProvider("strong");
        Map<String, AiProperties.Route> routes = new LinkedHashMap<>();

        AiProperties.Route finance = new AiProperties.Route();
        finance.setProvider("strong");
        routes.put("finance", finance);

        AiProperties.Route batch = new AiProperties.Route();
        batch.setProvider("embed");
        batch.setFallbackProvider("cheap");
        routes.put("batch", batch);

        props.setRoutes(routes);
        return props;
    }

    private static ModelProviderRegistry registry(AiProperties props) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        ObjectProvider<ObservedModelCall> observers = factory.getBeanProvider(ObservedModelCall.class);
        return new ModelProviderRegistry(
                List.of(new Fake("cheap"), new Fake("strong"), new Fake("embed")), props, observers);
    }

    private static ModelRouter router(AiProperties props) {
        return new ModelRouter(registry(props), props);
    }

    private static Agent agent(String route) {
        return new Agent() {
            @Override
            public String name() {
                return "demo";
            }

            @Override
            public String description() {
                return "测试用";
            }

            @Override
            public Set<String> toolNames() {
                return Set.of();
            }

            @Override
            public String systemPrompt() {
                return "只做测试";
            }

            @Override
            public String modelRoute() {
                return route;
            }
        };
    }

    /** 只用于解析路由，不真的调模型 */
    private record Fake(String id) implements ModelProvider {

        @Override
        public ModelReply chat(List<ChatMessage> messages, boolean jsonMode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelReply stream(List<ChatMessage> messages, Consumer<String> onDelta) {
            throw new UnsupportedOperationException();
        }
    }

    private static AutoCloseable label(String route) {
        RunContext.set(RunContext.Subject.of("u-1", Map.of(ModelRouter.LABEL, route)));
        return RunContext::clear;
    }
}
