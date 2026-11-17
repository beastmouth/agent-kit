package com.beastmouth.agentkit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型提供方与 Agent 运行参数。
 *
 * <p>换模型只改配置，不改代码：{@code provider} 选实现，内置三种渠道——</p>
 * <ul>
 *   <li>{@code deepseek}：DeepSeek 官方（OpenAI 兼容，模型名 {@code deepseek-flash}）</li>
 *   <li>{@code siliconflow}：硅基流动（OpenAI 兼容，同一套代码，只换 {@code base-url} 与 {@code model}）</li>
 *   <li>{@code ollama}：本地 Ollama，常驻、免费、质量弱</li>
 * </ul>
 * <p>要接新厂商（通义 / vLLM / one-api…），只要是 OpenAI 兼容协议，
 * 加一个 {@link com.beastmouth.agentkit.provider.OpenAiCompatibleProvider} 的子类声明 id 即可。</p>
 *
 * <p>主备共存：{@code provider} 是首选，{@code fallback-provider} 是它失败时的兜底。
 * 两个提供方的地址/模型/密钥各不相同，因此放在 {@link #endpoints} 里按 id 分别配置，
 * 顶层 {@code base-url} / {@code model} / {@code api-key} 只作为首选提供方（或未单独配置者）的默认值。</p>
 */
@Data
@ConfigurationProperties(prefix = "agent-kit")
public class AiProperties {

    /** 总开关：关闭后 /api/agent/** 返回 503，前端可隐藏入口 */
    private boolean enabled = true;

    /** 首选提供方 id，对应 ModelProvider#id() */
    private String provider = "ollama";

    /**
     * 兜底提供方 id，留空表示不兜底。
     *
     * <p>云端模型会有余额、限流、网络抖动等失败，本地 Ollama 则常驻可用；
     * 配上兜底后，云端挂了对话仍然能答，只是质量降到本地模型（日志会记一条 warn）。</p>
     */
    private String fallbackProvider = "";

    /**
     * 过载熔断冷却（秒）：首选提供方失败降级后的这段时间内，新请求直接走兜底，不再撞首选。
     *
     * <p>背景：2026-11-03 DeepSeek 过载，单请求挂 18~25 秒才返回 503，过载窗口里每轮对话
     * 都要白烧一次首选的超时。「失败→立刻降级」只能省掉重试，省不掉这次等待；
     * 熔断把「后续每轮都撞一次」变成「冷却期内直接走备」，把过载期的最坏耗时压下来。
     * 冷却过了自动恢复首选，无需人工介入。设为 0 关闭熔断。</p>
     */
    private int failoverCooldownSeconds = 60;

    /** 服务地址，Ollama 默认 http://localhost:11434 */
    private String baseUrl = "http://localhost:11434";

    /** 模型名，例如 deepseek-r1:8b / qwen2.5:7b */
    private String model = "qwen3:8b";

    /** 云端提供方的 API Key，本地 Ollama 留空 */
    private String apiKey = "";

    /** 按提供方 id 单独配置的端点，键名与 ModelProvider#id() 一致 */
    private Map<String, Endpoint> endpoints = new LinkedHashMap<>();

    /**
     * 按业务分流的渠道组合：路由键 → 渠道（可带独立兜底）。
     *
     * <p>上面的 {@link #provider} 是<b>默认渠道</b>，只在没命中任何路由时才用。命中规则见
     * {@code ModelRouter}：请求标签（{@code RunContext.labels["model.route"]}）优先，
     * 其次子 Agent 自己声明的 {@code Agent#modelRoute()}，都没有才回落默认。</p>
     *
     * <p><b>为什么路由内的兜底不继承全局 {@link #fallbackProvider}</b>：全局兜底通常配本地
     * Ollama，而走独立路由的业务往往恰恰是最不能降级的（数据不出域、输出必须过合规模型）。
     * 继承过来等于「A 渠道抖一下，业务被悄悄挪到 B 渠道」，成本、合规、数据流向三件事同时
     * 失控，且只留一条 warn。所以路由内的兜底只能在 {@link Route#getFallbackProvider()} 里
     * 显式写。</p>
     */
    private Map<String, Route> routes = new LinkedHashMap<>();

    /**
     * 意图路由（判断「这句话归哪个子 Agent」）那一步使用的渠道 id，留空表示跟随本次请求的路由。
     *
     * <p>这一步每轮必跑、提示词很短、只输出一个 JSON，用强模型属于纯浪费；
     * 典型配法是给它一个本地或小模型渠道。刻意只接受<b>单个渠道 id</b>（不接受路由键）：
     * 这一步失败后面还有三层兜底（关键词 → 追问延续 → 直答），不需要模型级主备，
     * 少一层组合少一处能配错的地方。</p>
     */
    private String intentProvider = "";

    /** 采样温度，抽取参数的场景尽量低 */
    private double temperature = 0.1;

    /**
     * 是否让模型输出思考过程。
     *
     * <p>{@code false} 时向提供方透传 {@code think=false}，并在提示词里追加「直接给结论」；
     * {@code null} 表示不传该字段，沿用模型默认。注意：<b>推理型模型（如 deepseek-r1）会忽略该参数</b>，
     * 要真正关掉思维链只能换非推理模型（如 qwen2.5、qwen3-nothink）。</p>
     */
    private Boolean think = false;

    /** 单次模型调用超时（秒）。推理模型单轮可能很慢，不要设太小 */
    private int timeoutSeconds = 300;

    /**
     * 流式请求是否显式索要 usage（{@code stream_options.include_usage = true}）。
     *
     * <p>不开就是现在这样：流式收尾只有文本，token 与缓存命中全部为 0，只能按字符估算，
     * 成本报表里流式调用永远是「估」的。DeepSeek / 硅基流动 / one-api 都支持该字段；
     * 万一某个网关不认（返回 400），代码会自动去掉该字段重试一次，不影响对话。</p>
     */
    private boolean streamUsage = true;

    /**
     * 最终答复是否流式输出（默认开）。
     *
     * <p><b>不是所有渠道都能流</b>：企业内网反向代理常把 SSE 缓冲到收完才吐（用户看到的
     * 效果是「转圈 20 秒、然后一次全出来」，比不流式更糟）；部分 one-api 网关、老版本 vLLM
     * 对 {@code stream=true} 直接回 400。这类环境关掉即可，代价是首字延迟变成总耗时。</p>
     *
     * <p>按渠道单独覆盖见 {@link Endpoint#getStream()}：一条链路里云端流式、本地不流式
     * 是常见组合。注意这里只管「给用户看的最终答复」——中间决策步本来就是同步 JSON，
     * 意图路由与质量评判也不需要流式。</p>
     */
    private boolean stream = true;

    /** 单次决策的输出上限（token）。推理型模型的思维链也吃这个额度 */
    private int maxOutputTokens = 3000;

    /** 子 Agent 单轮最多执行几次工具，防止死循环 */
    private int maxSteps = 4;

    /** 多轮上下文保留的历史消息条数（按会话） */
    private int historyLimit = 12;

    /**
     * L1 上下文包缓存开关（{@code agent-kit:ctx:*}）。
     *
     * <p>开启后会话历史先读 Redis，miss 才回源 MySQL 并回填（写穿）。命中时每轮对话
     * 省掉「会话归属校验 + 历史查询 + lastAgent 查询」三次数据库访问。缓存永远是优化
     * 不是正确性前提：Redis 任何异常都降级为 miss，关掉开关即回退纯 MySQL。</p>
     */
    private boolean contextCacheEnabled = true;

    /** 上下文包 TTL（秒）。过期只是回源一次 MySQL，不会错——一致性靠写穿不靠 TTL */
    private int contextCacheTtlSeconds = 1800;

    /**
     * 系统提示词的拼装顺序。
     *
     * <p>{@code v1} = 旧顺序（记忆排在工具与协议之前，每轮记忆一变就把后面 1–2k token 冲掉）；
     * {@code v2} = 稳定前缀（工具/协议/风格在前，日期与记忆压到最后）。</p>
     *
     * <p>顺序会影响模型对「记忆算系统设定还是用户陈述」的分量，属于会改变回答质量的参数，
     * 所以留成开关而不是直接改死：灰度期间可以一键切回，出问题不用回滚镜像。</p>
     */
    private String promptOrder = "v1";

    /**
     * 追加在系统提示词末尾的业务补充说明。
     *
     * <p>术语、单位、行业规范、禁止行为这类东西属于业务知识，脚手架不该内置任何一条。
     * 但提示词里又必须有，否则模型不知道怎么说话——所以留这个口子，由使用方按需填写。
     * 支持换行，会原样拼在系统提示词最后。</p>
     *
     * <p>位置刻意在末尾：它属于「本部署固定」的内容，放前面会与工具清单、协议一起构成
     * 前缀缓存的稳定区；一旦这段因业务调整而变，只需重算这一段之后的部分。</p>
     */
    private String extraInstructions = "";

    /** 每个归属方最多保留的会话数，超出后拒绝新建 */
    private int maxSessionsPerOwner = 50;

    /**
     * 对话线程池的常驻线程数。
     *
     * <p>一轮对话 = 一个线程阻塞在模型 HTTP 调用上（几十秒到几分钟），所以线程数按
     * 「同时能跑几轮对话」设，不按 CPU 核数设。超出后进队列，队列也满了直接拒绝并提示忙。
     * 无界队列在老实现里是隐患：流量峰值时线程与堆一起涨，最后 OOM 而不是降级。</p>
     */
    private int chatPoolSize = 8;
    /**
     * 对话排队上限：满了以后立刻回「人多稍后再试」，比让用户干等 3 分钟好。
     *
     * <p>不要设太大：线程池是「核心满了先排队，队列满了才扩容」，
     * 队列过长会让请求在队列里等到前端超时，而线程数根本没涨。</p>
     */
    private int chatPoolQueue = 50;

    /** 线程池空闲线程回收时间（秒）。对话不是常驻负载，闲下来要还回操作系统 */
    private int chatPoolKeepAliveSeconds = 60;

    /**
     * 取某个提供方实际生效的端点参数：单独配置优先，缺省项回落到顶层默认值。
     *
     * <p>不做共享对象引用，避免某个提供方误改到别家的配置。</p>
     */
    public Endpoint endpoint(String id) {
        Endpoint defaults = new Endpoint();
        defaults.setBaseUrl(baseUrl);
        defaults.setModel(model);
        defaults.setApiKey(apiKey);
        defaults.setThink(think);

        Endpoint custom = endpoints == null ? null : endpoints.get(id);
        if (custom == null) {
            return defaults;
        }
        Endpoint merged = new Endpoint();
        merged.setBaseUrl(StringUtils.hasText(custom.getBaseUrl()) ? custom.getBaseUrl() : defaults.getBaseUrl());
        merged.setModel(StringUtils.hasText(custom.getModel()) ? custom.getModel() : defaults.getModel());
        merged.setApiKey(StringUtils.hasText(custom.getApiKey()) ? custom.getApiKey() : defaults.getApiKey());
        merged.setThink(custom.getThink() != null ? custom.getThink() : defaults.getThink());
        // 思考开关与强度没有全局默认值：不配就不发字段，避免不认这些字段的网关直接 400
        merged.setThinking(custom.getThinking());
        merged.setReasoningEffort(StringUtils.hasText(custom.getReasoningEffort()) ? custom.getReasoningEffort() : null);
        merged.setStream(custom.getStream());
        return merged;
    }

    /**
     * 某个渠道最终是否流式：渠道单独配置优先，否则跟随全局。
     *
     * <p>放在配置类上而不是各 provider 里，是为了两套引擎算同一件事时不会各写一份默认值。</p>
     */
    public boolean streamOf(String id) {
        Boolean custom = endpoint(id).getStream();
        return custom != null ? custom : stream;
    }

    /** 单个提供方的端点参数 */
    @Data
    public static class Endpoint {

        /** 服务地址，如 https://api.siliconflow.cn/v1 */
        private String baseUrl;

        /** 模型名，如 deepseek-ai/DeepSeek-V4.1-Flash */
        private String model;

        /** 该厂商的 API Key，本地服务留空 */
        private String apiKey;

        /**
         * 本地 Ollama 的思考开关（对应请求体的 {@code think}），null 表示不传。
         *
         * <p>只对 {@code ollama} 渠道生效；OpenAI 兼容渠道的请求体里没有这个字段，
         * 它们用下面的 {@link #thinking}。</p>
         */
        private Boolean think;

        /**
         * OpenAI 兼容渠道的思考开关（对应请求体的 {@code thinking:{type:enabled|disabled}}）。
         *
         * <p>{@code null} 时不发送该字段——不是所有网关都认它，多传可能直接 400。
         * DeepSeek 官方支持：关掉后单轮从 ~1.4s 降到 ~1.1s（短句场景），长回答省得更多。</p>
         */
        private Boolean thinking;

        /** 思考强度（对应请求体的 {@code reasoning_effort}），仅部分厂商支持，null 不发送 */
        private String reasoningEffort;

        /**
         * 该渠道是否流式，覆盖顶层 {@link AiProperties#stream}。
         *
         * <p>{@code null} = 跟随全局。存在的理由是渠道差异：同一个部署里，云端走 SSE 没问题，
         * 本地 Ollama 对流式的首字延迟几乎没优势，而某些网关压根不认 {@code stream=true}。
         * 一个渠道配错不该逼所有人关掉流式。</p>
         */
        private Boolean stream;
    }

    /**
     * 一条业务路由：把「某个业务场景」固定到一个渠道组合上。
     *
     * <p>键名（{@code agent-kit.routes.<键>}）就是路由键，由业务侧通过
     * {@code RunContext.labels} 的 {@code model.route} 传入，或由子 Agent 的
     * {@code modelRoute()} 声明。键名大小写敏感，与标签值必须逐字一致。</p>
     */
    @Data
    public static class Route {

        /**
         * 首选提供方 id，对应 {@code ModelProvider#id()}；留空表示沿用全局 {@link #provider}。
         *
         * <p>留空是给「只想改兜底、主渠道不变」这种配置留的口子，不是常规写法。</p>
         */
        private String provider;

        /** 该路由单独的兜底提供方 id；<b>留空就是不兜底</b>，不会继承全局兜底 */
        private String fallbackProvider;

        /** 给人看的说明（进日志与健康检查），不参与任何逻辑 */
        private String description;
    }
}
