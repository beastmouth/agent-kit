package com.beastmouth.agentkit.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 长期记忆配置。
 *
 * <p>「跨会话记忆」的最小落地：MySQL 存记忆正文（事实源），Milvus 存向量索引做语义检索，
 * Embedding 走 OpenAI 兼容的 {@code /v1/embeddings}（本地 Ollama 的 bge-m3，也可切云端嵌入）。</p>
 *
 * <p>任何一项基础设施不可用（Milvus 掉线、嵌入服务失败）都不能影响正常对话：
 * 检索降级为空列表，提炼静默失败留日志，等下一轮再试。</p>
 */
@Data
@ConfigurationProperties(prefix = "agent-kit.memory")
public class MemoryProperties {

    /**
     * 默认的提炼提示词。
     *
     * <p>只描述「什么值得记」这类<b>与业务无关</b>的判定标准；业务特有的口径
     * （该记哪些字段、不能记什么敏感数据）请用 {@code agent-kit.memory.consolidate-prompt}
     * 整体替换，不要在脚手架里写死。</p>
     */
    public static final String DEFAULT_CONSOLIDATE_PROMPT = """
            你是记忆管家。根据本轮对话，判断是否要更新这位调用方的长期记忆。

            只保存跨会话有用的<b>稳定</b>信息，四类：
            - RULE 行为规则：调用方纠正过你的处理口径，「遇到 X → 必须 Y」。
              只要本轮在纠正你（说错了、归类错了、口径不对），必须提炼一条 RULE。
            - PREFERENCE 偏好：回答风格、格式、工具选择、单位与措辞习惯。
            - FACT 稳定事实：与任务长期相关且不会频繁变化的事实。
            - HABIT 表达习惯：常用说法、简称、默认口径（如「默认指本月」）。

            <b>冲突必须 UPDATE，不许并存</b>：调用方改主意、推翻之前的口径
            （「之前说的不对」「其实应该 XX」「不这样了」），必须对现有记忆列表里
            对应那条执行 UPDATE（整句替换成新口径）或 DROP，绝不允许新旧两条同时生效。
            拿不准指的是哪条时才用 ADD，并在 content 里写清适用条件。

            <b>shared 作用域</b>：这条信息在同一分区内对所有调用方都适用时，op 里加 "shared":true；
            对现有 [共享] 标记的记忆做 UPDATE 且仍然通用时，同样加 shared:true。
            没提就省略，默认只属于当前调用方。

            不要记录：可从数据源直接查到的明细、当前对话的临时上下文、闲聊、一次性问题。
            宁缺毋滥，没有值得记的就返回空数组。

            只输出一个 JSON 对象，不要解释、不要 markdown 代码块：
            {"ops":[
              {"action":"ADD","kind":"RULE","content":"遇到X时应该Y（一句话规则）","shared":true},
              {"action":"ADD","kind":"PREFERENCE","content":"一句话记忆"},
              {"action":"UPDATE","id":123,"kind":"FACT","content":"修正后的完整记忆（整句替换，不是增量）"},
              {"action":"UPDATE","id":124,"kind":"RULE","content":"新口径","shared":true},
              {"action":"DROP","id":456}
            ]}
            ADD 的 content 必须是脱离上下文也能看懂的一句话（含主语）。
            UPDATE/DROP 的 id 只能来自下面的现有记忆列表（含 [共享] 的记忆）。

            今天是 %s。

            【现有记忆】（[共享] 开头的是分区内共享记忆，所有调用方都会被注入）
            %s

            【本轮对话】
            用户：%s
            助手：%s
            """;

    /** 总开关：关闭后不提炼、不检索，行为与没有记忆模块时完全一致 */
    private boolean enabled = true;

    /** 嵌入服务地址（OpenAI 兼容，含 /v1）。本地 Ollama 默认 http://localhost:11434/v1 */
    private String baseUrl = "http://localhost:11434/v1";

    /** 嵌入服务 API Key，本地服务留空 */
    private String apiKey = "";

    /** 嵌入模型名，例如 bge-m3（1024 维）/ text-embedding-3-small */
    private String model = "bge-m3";

    /** 向量维度，必须与 Milvus collection 建库时一致 */
    private int dimensions = 1024;

    /** Milvus gRPC 地址，例如 http://host.docker.internal:19530 */
    private String milvusUri = "http://localhost:19530";

    /**
     * Milvus 用户名。Milvus 开启 common.security.authorizationEnabled 后必填；
     * 留空表示匿名连接（兼容未开认证的实例）。
     */
    private String milvusUsername = "";

    /** Milvus 密码，与 {@link #milvusUsername} 成对使用 */
    private String milvusPassword = "";

    /** Milvus collection 名 */
    private String collection = "ai_agent_memory";

    /** 每轮对话检索的记忆条数上限 */
    private int topK = 5;

    /**
     * 规则（RULE）记忆的独立槽位数。
     *
     * <p>规则来自调用方纠正，数量会随使用增长；不单独留槽的话，规则多了会在
     * 相似度排序里把普通偏好/事实记忆全部挤出去。注入时规则最多占这些槽，
     * 剩余名额给其他类型；规则不足时名额让渡给普通记忆，总注入条数仍 ≤ topK。</p>
     */
    private int ruleTopK = 3;

    /**
     * 提炼与自检用的模型渠道 id（{@link com.beastmouth.agentkit.provider.ModelProvider#id()}）。
     *
     * <p>空串表示沿用对话主链路的提供方（含主备组合）。默认 ollama：提炼是异步后台任务，
     * 时延不敏感，本地小模型免费且数据不出门。配置的渠道不存在或调用失败时只降级为
     * 「本轮不提炼」，绝不回落到云端硬顶成本。</p>
     */
    private String chatProvider = "ollama";

    /** 相似度下限（COSINE）。低于此值视为不相关丢弃 */
    private double minScore = 0.35;

    /** 单个归属方的记忆条数上限，防止无限膨胀 */
    private int maxPerOwner = 200;

    /**
     * 提炼提示词模板，含四个占位符（按顺序）：
     * {@code 今天日期 / 现有记忆列表 / 用户原话 / 助手回复}。
     *
     * <p>模板必须以「只输出 JSON」结尾并遵守 {@code {"ops":[...]}} 的结构，
     * 否则解析失败会被静默跳过（不会影响对话，但也记不住任何东西）。</p>
     */
    private String consolidatePrompt = DEFAULT_CONSOLIDATE_PROMPT;

    public boolean valid() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(milvusUri) && dimensions > 0;
    }
}
