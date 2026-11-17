package com.beastmouth.agentkit.session;

import com.baomidou.mybatisplus.annotation.TableName;
import com.beastmouth.agentkit.support.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 会话中的一条消息（只存用户可见内容）。
 *
 * <p>刻意不存子 Agent 的工具往返与模型原始输出：多轮上下文只保留「人说的话」，
 * 既控制 token 增长，也避免上一轮的中间态污染下一轮判断。</p>
 *
 * <p>{@code namespace} / {@code owner} 从所属会话冗余一份：按会话查消息时能多一层
 * 隔离条件（业务侧做数据迁移、按分区清理都用得上），而写入时直接跟着会话走，
 * 不会出现「消息与会话不同分区」的脏数据。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_agent_message")
public class AgentMessage extends BaseEntity {

    /** 所属会话 */
    private Long sessionId;

    /** 分区键，继承自会话 */
    private String namespace;

    /** 归属方，继承自会话 */
    private String owner;

    /** USER / ASSISTANT */
    private String role;

    /** 回答的子 Agent，用户消息为空 */
    private String agentName;

    /** 正文 */
    private String content;

    /** 本轮执行过的工具动作，JSON 数组，便于前端回放「做过什么」 */
    private String toolEvents;
}
