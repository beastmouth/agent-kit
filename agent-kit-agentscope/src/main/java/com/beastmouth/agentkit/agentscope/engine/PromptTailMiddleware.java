package com.beastmouth.agentkit.agentscope.engine;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/**
 * 把「每轮都变」的内容追加到系统提示词末尾：当前日期、（可选）思考开关、长期记忆。
 *
 * <p><b>顺序是刻意设计，不是随手排的。</b>DeepSeek 一类服务的前缀缓存按前缀<b>完全匹配</b>
 * 计费，命中价常是未命中的 1/50。只要有一处「每轮都变」的内容排到前面，
 * 它后面的稳定内容就全部作废：</p>
 * <ul>
 *   <li>{@code v1}（旧顺序）：角色 → 日期 → 思考开关 → 记忆 → 工具 → 协议 → 风格。
 *       记忆每轮重新 recall，一变就把后面一两千 token 的工具与协议全冲掉，实测第二轮命中率 0；</li>
 *   <li>{@code v2}（推荐）：角色 → 工具 → 协议 → 风格 → 日期/开关 → 记忆。
 *       稳定内容在前，实测第二轮命中 73.5%。</li>
 * </ul>
 *
 * <p>本中间件承担的正是 v2 的尾部那一段。工具在 AgentScope 里走原生 tools 字段（也是稳定的），
 * 所以稳定前缀天然成立，这里只需保证「日期和记忆永远在最后」。</p>
 *
 * <p>为什么用中间件而不拼进 {@code sysPrompt}：{@code sysPrompt} 在 Agent 构建时固定，
 * 而日期与记忆每轮不同；重建 Agent 又会让 Toolkit 重新解析全部工具 JSON Schema。
 * 中间件每轮执行、开销可忽略。</p>
 */
public class PromptTailMiddleware implements MiddlewareBase {

    private static final String MEMORY_HEADER = "\n\n[相关记忆]\n";
    private static final String NO_THINK_HINT = "\n直接给出最终结果，不要输出思考过程、推理步骤或额外解释。";

    private final boolean appendNoThinkHint;

    public PromptTailMiddleware(boolean appendNoThinkHint) {
        this.appendNoThinkHint = appendNoThinkHint;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String original) {
        StringBuilder sb = new StringBuilder(original == null ? "" : original);

        // 1. 日期：必须在，否则模型会用训练截止日期去算「今天」，把事记到几年前
        sb.append("\n\n今天是 ").append(LocalDate.now()).append('.');

        // 2. 思考开关：只是提示词层面的引导，真正关思考靠请求体里的 thinking=false
        if (appendNoThinkHint) {
            sb.append(NO_THINK_HINT);
        }

        // 3. 记忆压最后：它是每轮唯一必然变化的内容，放在这里冲不掉任何稳定前缀
        List<String> memories = ctx == null ? null : ctx.get(AgentScopeEngine.CTX_MEMORIES, List.class);
        if (memories != null && !memories.isEmpty()) {
            sb.append(MEMORY_HEADER);
            for (String memory : memories) {
                if (memory != null && !memory.isBlank()) {
                    sb.append("- ").append(memory).append('\n');
                }
            }
        }
        return Mono.just(sb.toString());
    }
}
