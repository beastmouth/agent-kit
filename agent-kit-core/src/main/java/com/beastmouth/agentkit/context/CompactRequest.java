package com.beastmouth.agentkit.context;

import com.beastmouth.agentkit.model.ChatMessage;

import java.util.List;

/**
 * 一次压缩请求。
 *
 * @param scope       隔离键，用于摘要缓存的命名空间。通常传调用方标识
 *                    （{@code RunContext.subjectId()}），可为 null——为空时摘要按内容全局共享
 * @param history     准备送给模型的历史（时间正序，不含本轮用户输入）
 * @param userMessage 本轮用户输入，仅用于生成摘要时给模型一个「当前在聊什么」的锚点
 * @param force       跳过阈值直接压缩。只在上下文溢出兜底时用：模型已经报错了，没得选
 *
 * <h3>为什么不带 sessionId</h3>
 * <p>摘要的版本号是<b>前缀内容指纹</b>（见 {@link LlmContextCompactor}），指纹本身就是
 * 内容维度的身份：内容一致就必然可以复用摘要，与它属于哪个会话无关。
 * 带上 sessionId 反而会让「同一段内容在两个会话里各生成一份摘要」，白花一次 LLM 调用。</p>
 *
 * <p>更现实的原因是：{@code ChatEngine#handle} 的签名是冻结契约，里面没有 sessionId；
 * 为了压缩去改冻结契约，代价远大于收益。</p>
 */
public record CompactRequest(String scope, List<ChatMessage> history, String userMessage, boolean force) {

    /**
     * 刻意<b>不</b>复制 history。
     *
     * <p>压缩是热路径（每轮都调），而「未超阈值」是绝大多数轮次的结果——如果这里复制一份，
     * 每轮都白拷一次列表，还让「返回原引用 = 没压缩」这个约定失效（引擎靠它判断能否重试）。
     * 压缩器对 history 只读，调用方在调用后也不会改它，复制没有收益。</p>
     */
    public CompactRequest {
        history = history == null ? List.of() : history;
    }

    /** 常规压缩（按阈值判断） */
    public static CompactRequest of(String scope, List<ChatMessage> history, String userMessage) {
        return new CompactRequest(scope, history, userMessage, false);
    }

    /**
     * 溢出兜底：强制压缩。
     *
     * <p>与常规压缩的区别只有一个——不再看阈值。因为触发它的场景是
     * {@code context_length_exceeded}，此时「还没到阈值」这个判断本身就是错的。</p>
     */
    public CompactRequest forced() {
        return new CompactRequest(scope, history, userMessage, true);
    }
}
