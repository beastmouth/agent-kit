package com.beastmouth.agentkit.memory;

import com.baomidou.mybatisplus.annotation.TableName;
import com.beastmouth.agentkit.support.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 一条长期记忆（跨会话的稳定信息）。
 *
 * <p><b>可见范围只有两个自由度</b>，都用不透明字符串表达：</p>
 * <ul>
 *   <li>{@code namespace}：分区键（租户 / 组织 / 项目 / 机器人实例，业务自定义）。检索与提炼
 *       必须显式带上它，否则会跨分区读到别人的记忆——而这类错误不报错，只是答得不对。</li>
 *   <li>{@code owner}：归属方（用户 id / 工单号 / 设备号）。<b>{@code owner == null} 表示
 *       该分区内共享</b>：任何 owner 检索时都会带上它。共享语义不单独建字段，少一个状态就少一处
 *       不一致——之前用 scope 字段表达共享，结果 UPDATE 一条共享记忆时忘了同步 scope，
 *       记忆就悄悄变成了个人记忆。</li>
 * </ul>
 *
 * <p>正文以本表为准；Milvus 只存 (id, namespace, owner, vector) 索引，
 * 其中共享记忆的 owner 写空串（向量库的标量字段不支持 null）。
 * UPDATE/DROP 不物理删：置 {@code SUPERSEDED} 保留「以前是什么口径」的痕迹，
 * 检索与提炼都只看 {@code ACTIVE}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_agent_memory")
public class AgentMemory extends BaseEntity {

    /** 生效中 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 已被新记忆取代（先写后删的历史层） */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    /** 偏好（回答风格、格式、工具选择、措辞习惯……） */
    public static final String KIND_PREFERENCE = "PREFERENCE";
    /** 稳定事实（与任务长期相关、不频繁变化的信息） */
    public static final String KIND_FACT = "FACT";
    /** 表达习惯（「开销」默认指当月……） */
    public static final String KIND_HABIT = "HABIT";
    /**
     * 行为规则：「遇到 X → 应该 Y」的确定性口径，来自调用方对助手的纠正。
     *
     * <p>与 PREFERENCE 的区别：偏好是「倾向于」，规则是「必须」；检索注入时规则
     * 有独立槽位且排在前面，保证纠正一次之后同类场景永久生效。</p>
     */
    public static final String KIND_RULE = "RULE";

    /** 分区键，见类注释 */
    private String namespace;

    /** 归属方；{@code null} 表示分区内共享 */
    private String owner;

    /** 类型：RULE / PREFERENCE / FACT / HABIT */
    private String kind;

    /** 记忆正文，一句话 */
    private String content;

    /** 提炼来源会话 */
    private Long sourceSessionId;

    /** ACTIVE / SUPERSEDED */
    private String status;
}
