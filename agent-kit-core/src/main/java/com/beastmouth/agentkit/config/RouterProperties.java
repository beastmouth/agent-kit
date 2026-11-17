package com.beastmouth.agentkit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由器的业务补充配置。
 *
 * <p>脚手架只提供「怎么路由」的机制（模型语义路由 + 关键词兜底 + 短句延续），
 * 「哪些词属于哪个领域」「哪些能力边界算超出范围」是业务知识，必须由使用方提供。
 * 这就是本类存在的意义：把领域知识从代码里挪到配置里。</p>
 */
@Data
@ConfigurationProperties(prefix = "agent-kit.router")
public class RouterProperties {

    /**
     * 路由提示词的追加说明，可写多行。
     *
     * <p>典型用途：划清两个相近子助手的边界（「A 负责 X，B 只接 A 做不到的事」），
     * 或声明能力边界（「预算、导出、改账号属于不支持的能力，交给反馈助手」）。
     * 留空则只有通用的路由协议。</p>
     */
    private String instructions = "";

    /**
     * 模型判「无关」时的直答话术。
     *
     * <p>留空则按已注册子 Agent 的能力描述自动生成一句，例如
     * 「我目前可以帮你：查询订单、处理退款」。写死话术适合有品牌口吻要求的产品。</p>
     */
    private String fallbackReply = "";

    /**
     * 关键词兜底：{@code 子 Agent 名 -> 关键词列表}。
     *
     * <p>只在模型判「无关」之后生效，用来救「把刚才那条删掉」这种没有领域特征词、
     * 小模型容易判成闲聊的句子。命中任一关键词即交给对应 Agent，由它的工具判断该不该做。</p>
     *
     * <p>选词要保守：单字（「删」「改」）太容易误伤闲聊，收「删掉 / 更正 / 记错」
     * 这类明确指向动作的组合说法更稳。</p>
     */
    private Map<String, List<String>> fallbackKeywords = new LinkedHashMap<>();

    /**
     * 短句追问的省略信号词。
     *
     * <p>「那前天呢」「继续」这类句子没有任何领域词，语义全靠上文。命中即延续上一轮
     * 实际处理业务的子 Agent——它手里有历史，接不住也会如实说，比回一句无关话术强。</p>
     */
    private List<String> followUpWords = List.of(
            "今天", "昨天", "前天", "本周", "上周", "这周", "本月", "上月", "上个月",
            "这个月", "最近", "今年", "去年", "继续", "还有呢", "然后呢");

    /** 短句追问的最大长度：超过就当成完整问题，交给模型路由 */
    private int followUpMaxLength = 10;
}
