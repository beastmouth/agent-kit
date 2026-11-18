package com.beastmouth.agentkit.context;

import com.beastmouth.agentkit.model.ChatMessage;

/**
 * 上下文压缩扩展点。
 *
 * <p>职责单一：拿一份「准备送给模型的历史消息」，还回一份<b>更短但信息不丢</b>的历史。
 * 它不碰存储、不改会话、不写消息——压缩结果只在本次调用内有效，落库的永远是原文。
 * 这条边界必须守住：一旦压缩结果写回 {@code ai_agent_message}，原始对话就永久丢了，
 * 事后对账、复盘、申诉全部没有依据。</p>
 *
 * <h3>为什么是接口而不是直接用某个实现</h3>
 * <p>按仓库约定（AGENTS.md §1），扩展点必须是「接口 + 注册收集 + 默认实现」。
 * 默认实现是 {@link LlmContextCompactor}；想换成「规则压缩」「按主题聚类」或者
 * 「直接接第三方摘要服务」，实现本接口注册成 Bean 即可，引擎源码一行不改。</p>
 *
 * <h3>可降级是硬要求</h3>
 * <p>压缩属于锦上添花：<b>任何异常都必须退化成「不压缩」</b>，把原列表原样还回去。
 * 不能因为摘要模型挂了、缓存读不到、前缀算不出来就让整轮对话失败——用户不会接受
 * 「因为摘要服务不可用，所以你不能提问」。</p>
 *
 * <h3>不实现时的行为</h3>
 * <p>容器里没有 {@code ContextCompactor} 时，引擎按 {@code null} 处理，直接跳过压缩，
 * 行为与引入本接口之前完全一致（即只受 {@code agent-kit.history-limit} 约束）。</p>
 */
public interface ContextCompactor {

    /** 实现标识，用于日志与排障。默认取类名，避免每个实现都写一遍 */
    default String id() {
        return getClass().getSimpleName();
    }

    /**
     * 是否启用。
     *
     * <p>引擎先问这个再决定要不要调 {@link #compact}：关闭时必须<b>零开销</b>，
     * 而不是「调进来再原样返回」——后者会白算一遍 token 估算与前缀哈希。</p>
     */
    default boolean enabled() {
        return false;
    }

    /**
     * 上下文溢出（模型报 {@code context_length_exceeded}）时，是否允许引擎强制压缩后重试一次。
     *
     * <p>默认 {@code false}：这是对<b>自定义实现</b>的保守假设——不确定它在强制模式下
     * 一定能压下来时，重试只是白打一次模型。默认实现按配置返回。</p>
     */
    default boolean retryOnOverflow() {
        return false;
    }

    /**
     * 压缩。
     *
     * <p>实现约定：</p>
     * <ul>
     *   <li>不需要压缩时返回 {@link CompactOutcome#passthrough}，不要返回 null；</li>
     *   <li>任何异常自己吞掉并降级为 passthrough，不允许抛给调用方；</li>
     *   <li>返回的 {@code history} 必须可直接送模型（角色合法、顺序正确）。</li>
     * </ul>
     */
    CompactOutcome compact(CompactRequest request);
}
