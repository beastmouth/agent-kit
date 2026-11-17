package com.beastmouth.agentkit.agent;

import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.support.AgentKitException;
import com.beastmouth.agentkit.support.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 主 Agent（自研引擎）：只做三件事——路由、委派、汇总。
 *
 * <p>它不直接持有任何业务工具，因此不会被某个领域的具体实现绑住；子 Agent 的提示词、
 * 工具往返、模型原始输出都留在 {@link SubAgentRunner} 内部，主 Agent 只拿到
 * {@link AgentResult}。这样即使以后接五个领域 Agent，主 Agent 的上下文也不会膨胀。</p>
 *
 * <p>路由结果是一个<b>列表</b>而不是单个名字：一句话可能同时横跨多个领域（「这个月超支了吗，
 * 顺便把餐饮的明细列出来」）。协议仍兼容 {@code {"agent":"x"}} 的单值写法，多值走
 * {@code {"agents":["x","y"]}} 并按数组顺序<b>串行</b>执行——串行是为了让后一个 Agent
 * 能看到前一个已经落库的结果，也避免两个 Agent 同时写同一笔账。</p>
 *
 * <p><b>路由逻辑已抽到 {@link IntentRouter}</b>：它同样被 AgentScope 引擎使用，
 * 两套引擎共用一份路由提示词与三层兜底，避免行为分叉。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = ChatEngine.ENGINE_PROPERTY, havingValue = ChatEngine.LEGACY,
        matchIfMissing = true)
public class MainAgent implements ChatEngine {

    private final AgentRegistry agentRegistry;
    private final SubAgentRunner subAgentRunner;
    private final IntentRouter intentRouter;

    @Override
    public String engineName() {
        return ChatEngine.LEGACY;
    }

    /**
     * 处理一轮对话。
     *
     * @param history     该会话的历史消息（只有用户可见的对话，不含工具往返）
     * @param userMessage 本轮输入
     * @param sink        流式回调
     */
    public AgentResult handle(List<ChatMessage> history, String userMessage, ChatSink sink) {
        return handle(history, userMessage, null, List.of(), sink);
    }

    public AgentResult handle(List<ChatMessage> history, String userMessage, String lastAgentName, ChatSink sink) {
        return handle(history, userMessage, lastAgentName, List.of(), sink);
    }

    /**
     * 处理一轮对话（带上一轮子 Agent 信息与长期记忆）。
     *
     * @param lastAgentName 会话里最近一次真正处理业务的子 Agent 名，可能为 null
     * @param memories      与本轮输入相关的长期记忆行文本（来自 AgentMemoryService#recall），可为空列表
     */
    @Override
    public AgentResult handle(List<ChatMessage> history, String userMessage, String lastAgentName,
                              List<String> memories, ChatSink sink) {
        if (agentRegistry.isEmpty()) {
            throw AgentKitException.of(ErrorCode.AGENT_DISABLED);
        }
        IntentRouter.Route route = intentRouter.route(history, userMessage, lastAgentName, sink);
        if (route.agents().isEmpty()) {
            // 未委派：文本已经通过 sink 流给调用方，这里原样返回供上层落库
            return new AgentResult("main", route.directReply(), List.of());
        }
        List<AgentResult> results = new ArrayList<>();
        List<Agent> agents = route.agents();
        for (int i = 0; i < agents.size(); i++) {
            Agent agent = agents.get(i);
            if (i > 0) {
                // 前一个 Agent 已经把正文流给前端了，这里补一个空行，
                // 否则两段回答会粘成一段（落库的 reply 是拼接后的，两边要一致）
                sink.delta("\n\n");
            }
            sink.status("delegate", agents.size() > 1
                    ? "正在处理（%d/%d）".formatted(i + 1, agents.size())
                    : "正在处理");
            try {
                results.add(subAgentRunner.run(agent, history, userMessage, memories, sink));
            } catch (RuntimeException e) {
                // 串行多个子 Agent 时，前面几段正文早就流给前端了。这时候上抛异常会让
                // SSE 连接断开：前半段回答停在界面半空，刷新后整段消失（库里只写了用户消息）。
                // 一个字都没输出（第一个就失败）才上抛——那种情况由异常处理器转成错误响应体。
                AgentResult done = merge(results);
                if (done.reply() == null || done.reply().isBlank()) {
                    throw e;
                }
                StreamFailure.report(sink, e);
                log.warn("MainAgent#handle#第 {} 个子 Agent 失败，保留前 {} 段正文收尾 agent={} err={}",
                        i + 1, results.size(), agent.name(), e.toString());
                return done;
            }
        }
        AgentResult merged = merge(results);
        log.info("MainAgent#handle#完成 agents={} 动作数={}", merged.agentName(), merged.events().size());
        return merged;
    }

    /** 把多个子 Agent 的结果拼成一条回复：空回复直接丢掉，避免出现连续空行 */
    private AgentResult merge(List<AgentResult> results) {
        if (results.size() == 1) {
            return results.get(0);
        }
        StringBuilder reply = new StringBuilder();
        Set<String> names = new LinkedHashSet<>();
        List<AgentResult.ToolEvent> events = new ArrayList<>();
        for (AgentResult result : results) {
            names.add(result.agentName());
            events.addAll(result.events());
            if (result.reply() == null || result.reply().isBlank()) {
                continue;
            }
            if (!reply.isEmpty()) {
                reply.append("\n\n");
            }
            reply.append(result.reply().strip());
        }
        // 任一段是半截的，整体就是半截：合并后仍要能被上层识别出来
        boolean partial = results.stream().anyMatch(AgentResult::partial);
        return new AgentResult(String.join("+", names), reply.toString(), events, partial);
    }
}
