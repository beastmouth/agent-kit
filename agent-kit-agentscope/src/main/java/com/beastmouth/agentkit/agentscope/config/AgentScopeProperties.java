package com.beastmouth.agentkit.agentscope.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentScope 引擎的专属配置。
 *
 * <p>放在这里而不是塞进 {@code AiProperties}：这些开关只有走框架引擎时才有意义，
 * 混进公共配置会让「切回 legacy 后这几个键还在文档里」变成长期误导。</p>
 */
@Data
@ConfigurationProperties(prefix = "agent-kit.agentscope")
public class AgentScopeProperties {

    /**
     * 空口回执守门开关。
     *
     * <p>开着时：模型声称「已完成」但本轮<b>零工具调用</b>，引擎自动追加一轮强制纠正。
     * 关掉只是少一道保险，对话行为不变。</p>
     */
    private boolean writeClaimGuard = true;

    /**
     * 「声称已完成」的判定正则。
     *
     * <p><b>为什么必须可配</b>：什么话算「回执」完全取决于业务口径。工单系统认「已派单」，
     * 记账系统认「已记」，客服系统认「已为您提交」——写死在脚手架里，换业务就会漏判
     * （漏判的表现不是报错，是假回执被当成正常回复发出去）。</p>
     *
     * <p>默认值覆盖中文里最常见的一批完成态措辞；英文业务可改成
     * {@code (?i)(done|completed|submitted|saved|created|updated|deleted)}。</p>
     *
     * <p>注意：这里只做「关键词命中」，真正的判定还要叠加「本轮零工具调用」这个条件，
     * 所以「已更新了三条记录」这种有工具支撑的正常回答不会被误抓。</p>
     */
    private String writeClaimPattern = "已完成|已处理|已提交|已保存|已创建|已新增|已更新|已修改|已删除"
            + "|操作成功|设置成功|添加成功|提交成功|处理成功|办理成功";

    /**
     * 工具调用时前端状态行的文案映射：{@code 工具名 → 展示文案}。
     *
     * <p>模型调工具时前端要显示「正在记账 / 正在查明细」这类人话。脚手架不知道
     * {@code order_create} 应该显示成什么，所以留成配置；没配的工具统一显示「正在处理」。
     * 不配也能跑——只是状态行不够具体。</p>
     *
     * <pre>{@code
     * agent-kit:
     *   agentscope:
     *     tool-labels:
     *       order_create: 创建订单
     *       order_query: 查订单
     * }</pre>
     */
    private Map<String, String> toolLabels = new LinkedHashMap<>();

    /** 工具没配文案时的兜底展示，留空则只显示工具名 */
    private String defaultToolLabel = "处理";
}
