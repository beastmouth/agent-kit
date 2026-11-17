package com.beastmouth.agentkit.agent;

import java.util.Set;

/**
 * 领域子 Agent 契约。
 *
 * <p>一个子 Agent = 一份系统提示词 + 一组工具白名单 + 一段能力描述。主 Agent 只通过
 * {@link #description()} 认识它，因此新增能力（预算、报表、购物清单…）不需要改主流程：
 * 实现本接口并注册为 Bean 即可。</p>
 *
 * <p>上下文隔离靠「工具白名单 + 独立消息历史」实现：每个子 Agent 只拿到自己领域的工具与
 * 最近若干轮对话，主 Agent 只接收它的结构化结果，不继承它的工具往返记录。</p>
 */
public interface Agent {

    /** 子 Agent 标识，也是落库时记录「由谁回答」的字段，改名等价于换一个 Agent */
    String name();

    /** 给主 Agent 看的路由描述，写清「什么类型的请求归你」 */
    String description();

    /** 可使用的工具名白名单 */
    Set<String> toolNames();

    /** 系统提示词（角色、约束、输出协议），不含工具清单——工具清单由运行器自动拼接 */
    String systemPrompt();
}
