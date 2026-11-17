package com.beastmouth.agentkit.agent;

import com.beastmouth.agentkit.agent.AgentResult.ToolEvent;
import com.beastmouth.agentkit.support.AgentKitException;
import com.beastmouth.agentkit.support.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * 流式跑到一半失败时的收尾。
 *
 * <h3>为什么需要单独一层</h3>
 * <p>流式与非流式的失败代价完全不同。非流式：请求还在服务端，抛异常 → 转成一个 JSON 错误
 * 响应体，用户看到「模型不可用」，重试即可。流式：响应头（200 + SSE）早就发出去了，
 * 正文也吐了一部分，这时候再抛异常只会让连接断开——</p>
 * <ul>
 *   <li>前端拿到的是「网络错误」，不会走错误处理逻辑，只会显示断线重连提示；</li>
 *   <li>已经流出去的那半句话在界面上停在半空，刷新后又整段消失（库里什么都没存）；</li>
 *   <li>客服看到的截图和日志里的异常完全对不上，因为日志只记了「断开」。</li>
 * </ul>
 *
 * <p>所以约定：<b>已经输出过正文之后再失败，一律推 {@link ChatSink#error} 帧、然后正常
 * 返回已产出的部分</b>；一个字都还没输出就失败，才原样上抛，交给异常处理器转成错误响应体。
 * 判断依据是「有没有输出过」，不是「异常发生在哪个类」——这条边界写错，两种情况会互相污染。</p>
 *
 * <h3>为什么状态仍记 FAIL</h3>
 * <p>{@code ai_run.status} 的口径只有 RUNNING / SUCCESS / FAIL 三个值。部分成功不新增第四种状态：
 * 报表里的「成功率 = SUCCESS / 总数」已经写死在若干看板与告警规则里，多一个 PARTIAL 会让这些
 * 查询<b>静默漏数</b>（既不报错也不为 0，只是少了一块）。部分产出对排查有用的信息是
 * 「回复长度不为 0 但 run 是 FAIL」——这比多一个枚举值更容易被现有工具发现。</p>
 */
@Slf4j
public final class StreamFailure {

    private StreamFailure() {
    }

    /**
     * 推一帧 error，并用已产出的正文组装结果。
     *
     * @param agentName 哪个子 Agent 出的问题，进结果与日志
     * @param reply     已经流给用户的正文（非空才应该走这里）
     * @param events    失败前已经完成的工具动作
     * @param sink      原始 sink；为 null 时只记日志
     * @param cause     真实异常，内部只用来推导错误码，原文不对外
     */
    public static AgentResult partial(String agentName, String reply, List<ToolEvent> events,
                                      ChatSink sink, Throwable cause) {
        ErrorCode code = resolve(cause);
        log.warn("StreamFailure#partial#流式中途失败，保留已输出正文 agent={} 正文字数={} 动作数={} code={} err={}",
                agentName, reply == null ? 0 : reply.length(), events == null ? 0 : events.size(),
                code.code(), cause == null ? "null" : cause.toString());
        report(sink, code);
        // partial=true 是关键：外层据此把 run 记成 FAIL。理由见类注释——
        // 部分产出如果算成功，成功率会随上游稳定性「看起来变好」，而用户拿到的是半句话
        return new AgentResult(agentName, reply == null ? "" : reply, events == null ? List.of() : events, true);
    }

    /**
     * 只推 error 帧，不动返回值。
     *
     * <p>调用方已经自己组织好了部分结果（例如示例的会话门面把已收集的增量拼回正文）时用这个。</p>
     */
    public static void report(ChatSink sink, Throwable cause) {
        report(sink, resolve(cause));
    }

    private static void report(ChatSink sink, ErrorCode code) {
        if (sink == null) {
            return;
        }
        try {
            sink.error(code);
        } catch (RuntimeException e) {
            // 错误帧本身推送失败（连接已断）：只记日志。这里再抛异常会把「已输出正文」
            // 那条返回路径也炸掉，等于把一次「部分成功」变成两次失败
            log.warn("StreamFailure#report#推错误帧失败（连接可能已断开） code={} err={}",
                    code.code(), e.toString());
        }
    }

    /**
     * 异常 → 错误码。
     *
     * <p>顺序有讲究：{@link AgentKitException} 自己带了码，必须优先用——
     * 它可能是 {@code SESSION_LIMIT}、{@code TOOL_FAILED} 这类与模型无关的错误，
     * 一律当成「模型不可用」会让用户被引导去查渠道，而真正的原因是别处。</p>
     */
    public static ErrorCode resolve(Throwable cause) {
        if (cause instanceof AgentKitException e) {
            return e.errorCode() == null ? ErrorCode.INTERNAL_ERROR : e.errorCode();
        }
        return isTransportFailure(cause) ? ErrorCode.MODEL_UNAVAILABLE : ErrorCode.INTERNAL_ERROR;
    }

    /**
     * 沿 cause 链判断是不是网络/超时类故障。
     *
     * <p>框架异常几乎都是包装类（{@code RuntimeException -> WebClientRequestException -> IOException}），
     * 只看最外层会漏，于是「连接超时」被归成「服务内部错误」，用户重试半天，
     * 管理员去翻业务日志，实际问题是渠道地址配错了。</p>
     */
    public static boolean isTransportFailure(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof IOException || t instanceof TimeoutException) {
                return true;
            }
            String name = t.getClass().getSimpleName();
            if (name.contains("WebClient") || name.contains("Http") || name.contains("Timeout")
                    || name.contains("Connect")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
