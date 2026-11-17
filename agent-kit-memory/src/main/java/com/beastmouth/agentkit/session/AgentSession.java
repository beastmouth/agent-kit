package com.beastmouth.agentkit.session;

import com.baomidou.mybatisplus.annotation.TableName;
import com.beastmouth.agentkit.support.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 一个对话会话。
 *
 * <p><b>归属用两个不透明字符串表达</b>，脚手架不解释它们的含义：</p>
 * <ul>
 *   <li>{@code namespace}：分区键。可以是租户、组织、项目、机器人实例——任何能把数据隔开的维度。
 *       同 namespace 内的会话才会出现在同一个列表里。</li>
 *   <li>{@code owner}：归属方。可以是用户 id、工单号、外部会话号。越权访问一律按「不存在」处理，
 *       不区分「没有这条」和「不是你的」，避免通过 ID 探测别人的会话。</li>
 * </ul>
 *
 * <p>还要按<b>渠道</b>分开：网页、IM、API 的对话是几条独立的时间线，混在一个列表里会出现
 * 「在网页里接着聊 IM 那半句话」这种串味操作，两端上下文也会互相污染。因此每条会话记
 * {@code channel}（网页 / IM / API，取值由业务自定义）与 {@code sourceId}（渠道内来源标识），
 * 非网页渠道的会话默认在网页侧只读。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_agent_session")
public class AgentSession extends BaseEntity {

    /** 渠道：网页端（默认渠道，可在网页侧继续读写） */
    public static final String CHANNEL_WEB = "WEB";

    /** 分区键，见类注释 */
    private String namespace;

    /** 归属方，见类注释 */
    private String owner;

    /** 会话标题，取首条用户消息的前若干字 */
    private String title;

    /**
     * 来源渠道，默认 {@link #CHANNEL_WEB}。
     *
     * <p>库里是 {@code NOT NULL DEFAULT 'WEB'}，其他取值完全由业务决定。</p>
     */
    private String channel;

    /**
     * 渠道内的来源标识。
     *
     * <p>例如 IM 侧存 open_id：解绑或换了 IM 账号后，网页要能判断这条会话
     * 「还是不是当前这个人现在绑的那个账号」，否则会看到早已失效的历史。
     * 网页会话为 {@code null}。</p>
     */
    private String sourceId;

    /** 最近一次回答的子 Agent，仅用于展示 */
    private String agentName;

    /** 消息条数，避免每次都 count */
    private Integer messageCount;

    /** 最后活跃时间，会话列表按它倒序 */
    private LocalDateTime lastMessageAt;

    /** 是否网页端可继续对话（非网页渠道的会话在网页上只读） */
    public boolean isWritable() {
        return channel == null || CHANNEL_WEB.equals(channel);
    }
}
