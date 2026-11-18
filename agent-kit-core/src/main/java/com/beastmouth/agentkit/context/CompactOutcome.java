package com.beastmouth.agentkit.context;

import com.beastmouth.agentkit.model.ChatMessage;

import java.util.List;

/**
 * 一次压缩的结果。
 *
 * @param history      压缩后的历史，可直接送模型；未压缩时就是原列表
 * @param summary      本次使用的摘要正文，未压缩时为 null
 * @param coveredKey   摘要覆盖范围的<b>前缀指纹</b>（见下），未压缩时为 null
 * @param coveredCount 被摘要替换掉的原消息条数
 * @param reused       摘要是否直接复用了已缓存版本（<b>不是</b>本次新生成的）
 * @param savedChars   估算省下的字符数，用于观测，不参与任何判定
 */
public record CompactOutcome(List<ChatMessage> history, String summary, String coveredKey,
                             int coveredCount, boolean reused, int savedChars) {

    /**
     * 未压缩。
     *
     * <p>刻意返回原引用而不是拷贝：未压缩是热路径（绝大多数轮次都不压缩），
     * 每轮复制一遍列表纯属浪费。</p>
     */
    public static CompactOutcome passthrough(List<ChatMessage> history) {
        return new CompactOutcome(history == null ? List.of() : history, null, null, 0, false, 0);
    }

    /** 是否真的发生了压缩 */
    public boolean compacted() {
        return summary != null;
    }
}
