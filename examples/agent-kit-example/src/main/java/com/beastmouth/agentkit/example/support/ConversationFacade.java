package com.beastmouth.agentkit.example.support;

import com.beastmouth.agentkit.agent.AgentResult;
import com.beastmouth.agentkit.agent.ChatEngine;
import com.beastmouth.agentkit.agent.ChatSink;
import com.beastmouth.agentkit.agent.StreamFailure;
import com.beastmouth.agentkit.context.RunContext;
import com.beastmouth.agentkit.eval.FeedbackService;
import com.beastmouth.agentkit.memory.AgentMemoryService;
import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.obs.RunScope;
import com.beastmouth.agentkit.session.AgentMessage;
import com.beastmouth.agentkit.session.AgentSession;
import com.beastmouth.agentkit.session.AgentSessionLock;
import com.beastmouth.agentkit.session.AgentSessionStore;
import com.beastmouth.agentkit.support.AgentKitException;
import com.beastmouth.agentkit.support.ErrorCode;
import com.beastmouth.agentkit.support.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 「会话 + 记忆」这一层适配，也是业务侧真正要抄的那 100 行。
 *
 * <p>脚手架把三件事拆散了：{@link AgentSessionStore} 只管消息与归属，
 * {@link AgentMemoryService} 只管长期记忆，{@link ChatEngine} 只管「历史 + 这句话 → 回答」。
 * 拆开是为了各自可测、可替换；代价是<b>拼装顺序</b>得有人负责——顺序错了不会报错，
 * 只会出现「模型看不到上一轮」「记忆串到别人身上」「同一会话并发重复写」这类安静的错误。</p>
 *
 * <h3>这里固化的顺序（改动前先想清楚为什么）</h3>
 * <ol>
 *   <li><b>先绑调用方</b>：{@code RunContext} 决定观测的 {@code subject_id} 与工具里的数据隔离范围；</li>
 *   <li><b>再定位会话</b>：{@code getWritable} 一次性完成归属校验（别人的会话按「不存在」处理）；</li>
 *   <li><b>记忆检索在锁外</b>：只读、可失败、耗时（要调嵌入 + 向量检索），塞进临界区只会拉长持锁时间；</li>
 *   <li><b>历史读取、写用户消息、调模型、写助手消息全在一个锁内</b>：否则同一会话的两个请求
 *       会读到同一份历史，各自追加，最后库里出现两条互不知情的回答；</li>
 *   <li><b>调模型前把 run 绑到会话上</b>（{@code RunScope.bindSession}）：不绑则
 *       {@code ai_run.session_id} 为空，「按会话看链路」与「点踩按会话倒查」两个功能静默失效；</li>
 *   <li><b>记忆提炼在锁外异步</b>：它是副作用（可能写库），慢且可失败，不能影响本轮返回。</li>
 * </ol>
 *
 * <p><b>为什么会话与记忆方法都要显式传 {@code namespace / owner}</b>：脚手架刻意不从这里
 * 偷偷读 {@code RunContext}。忘设上下文时，显式参数会编译不过或传 null 报错；
 * 而隐式读取只会静默读到空数据，答得不对但一切正常。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationFacade {

    private final AgentSessionStore sessionStore;
    private final AgentMemoryService memoryService;
    private final AgentSessionLock sessionLock;
    private final ChatEngine chatEngine;
    private final FeedbackService feedbackService;

    /**
     * 分区键（租户 / 组织 / 项目 / 机器人实例）。示例固定一个值；
     * 多租户系统从登录态取，<b>不要</b>让客户端传。
     */
    @Value("${demo.namespace:demo}")
    private String namespace;

    /** 一轮对话的对外结果 */
    public record Turn(Long sessionId, String agentName, String reply,
                       List<AgentResult.ToolEvent> tools, List<StreamEvent> events) {
    }

    /** 流式事件（示例收集起来一次性返回；真实项目直接推 SSE 帧） */
    public record StreamEvent(String type, String name, boolean success, String text) {
    }

    /**
     * 处理一轮对话。
     *
     * @param subject   调用方，由 Web 层从鉴权结果构造
     * @param sessionId 会话 id；{@code null} 表示新建
     * @param message   用户原话
     */
    public Turn chat(RunContext.Subject subject, Long sessionId, String message) {
        if (message == null || message.isBlank()) {
            throw AgentKitException.of(ErrorCode.INVALID_CONFIG, "消息不能为空");
        }
        String owner = subject == null ? null : subject.id();
        if (owner == null || owner.isBlank()) {
            // 没有调用方就没有归属，会话与记忆都会写进"公共池"，这在多用户系统里等于数据泄露
            throw AgentKitException.of(ErrorCode.INVALID_CONFIG, "缺少调用方标识，无法开始对话");
        }

        try (SubjectScope ignored = SubjectScope.open(subject)) {
            // ① 定位或新建会话：getWritable 顺带做归属校验，别人的会话按"不存在"处理
            AgentSession session = sessionId == null
                    ? sessionStore.create(namespace, owner, null, AgentSession.CHANNEL_WEB, owner)
                    : sessionStore.getWritable(sessionId, namespace, owner);

            // ② 记忆检索：锁外、可失败、失败返回空列表不抛
            List<String> memories = memoryService.recall(namespace, owner, message);

            CollectingSink sink = new CollectingSink();
            AtomicReference<AgentResult> holder = new AtomicReference<>();
            // ③ 同一会话串行：读历史 → 写用户消息 → 调模型 → 写助手消息
            boolean ran = sessionLock.tryRun(session.getId(), AgentSessionLock.WEB_WAIT_MILLIS, () -> {
                List<ChatMessage> history = sessionStore.recentHistory(session.getId(), namespace, owner);
                String lastAgent = sessionStore.lastDelegatedAgent(session.getId(), namespace, owner);
                sessionStore.append(session, "USER", null, message, null);
                // 把 run 与 session 绑上：ai_run.session_id 不落库的话，「按会话看链路」
                // 和「点踩按会话倒查 runId」两个功能全部失效，而且不报错
                RunScope.bindSession(session.getId());
                try {
                    AgentResult result;
                    try {
                        result = chatEngine.handle(history, message, lastAgent, memories, sink);
                    } catch (RuntimeException e) {
                        // 引擎层已经处理过「说到一半断了」；这里再兜一层是因为 ChatEngine 是开放接口，
                        // 自定义实现不一定守约定。判据只有一个：正文有没有真的发出去过。
                        // 没发过 → 原样上抛，让 Controller 回一个错误响应体（前端走错误处理）；
                        // 发过 → 补一帧 error + 把已输出的部分落库，让前端能保留内容并提示重试。
                        if (!sink.hasText()) {
                            throw e;
                        }
                        StreamFailure.report(sink, e);
                        log.warn("ConversationFacade#chat#引擎中途失败，保留已输出正文收尾 session={} 正文字数={} err={}",
                                session.getId(), sink.text().length(), e.toString());
                        // partial=true：库里存的是真实产出过的部分，但不是完整回答，
                        // 上层（IM 推送 / 客服工单）据此可以加一句「这条没说完」
                        result = new AgentResult("main", sink.text(), sink.toolEvents(), true);
                    }
                    AgentMessage saved = sessionStore.append(session, "ASSISTANT", result.agentName(),
                            result.reply(), Json.toJson(result.events()));
                    log.info("ConversationFacade#chat#落库完成 session={} message={} agent={}",
                            session.getId(), saved.getId(), result.agentName());
                    holder.set(result);
                } finally {
                    RunScope.unbindSession();
                }
            });
            if (!ran) {
                // 只有「真抢不到锁」才会走到这里。临界区里抛出的业务异常（比如模型挂了）
                // 会原样上抛——锁不该替模型背锅，这两件事的提示差别很大
                throw AgentKitException.of(ErrorCode.SESSION_BUSY,
                        "上一条消息还在处理（同一会话同时只能跑一轮），等它结束再发");
            }

            AgentResult result = holder.get();
            // ④ 记忆提炼：异步，失败只影响"下次记不记得"，不影响本轮回答
            memoryService.consolidateAsync(namespace, owner, session.getId(), message, result.reply());
            return new Turn(session.getId(), result.agentName(), result.reply(),
                    result.events(), sink.drain());
        }
    }

    /** 会话列表：只返回该调用方自己的（归属过滤在 Store 里，不在这里） */
    public List<AgentSession> sessions(String owner) {
        return sessionStore.list(namespace, owner);
    }

    /**
     * 提交反馈。
     *
     * <p>{@code runId} 传 {@code null} 时由 Store 按会话倒查最近一条 run——
     * 前端只知道"刚才那句话"，不知道 runId，这个便利是必需的。</p>
     */
    public Long feedback(String owner, Long sessionId, String rating, String tags, String comment) {
        try (SubjectScope ignored = SubjectScope.open(RunContext.Subject.of(owner))) {
            return feedbackService.submit(null, sessionId, owner, rating, tags, comment);
        }
    }

    /**
     * 收集流式事件。
     *
     * <p>示例里攒完再返回，方便用一个 JSON 响应看懂整轮发生了什么；
     * 真实项目把这几个回调直接转成 SSE 即可——{@link ChatSink} 的三个方法就是三帧。</p>
     */
    private static final class CollectingSink implements ChatSink {

        private final List<StreamEvent> events = new ArrayList<>();
        /** 与流式事件同步攒的工具动作：中途失败时要用它落库，不能丢质量规则需要的 rows 之外的字段 */
        private final List<AgentResult.ToolEvent> tools = new ArrayList<>();
        /** 已经推给前端的正文。中途失败时它就是「这一轮真实产出」的唯一凭证 */
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void status(String stage, String label) {
            events.add(new StreamEvent("status", stage, true, label));
        }

        @Override
        public void tool(String name, boolean success, String summary) {
            events.add(new StreamEvent("tool", name, success, summary));
            tools.add(new AgentResult.ToolEvent(name, success, summary));
        }

        @Override
        public void delta(String text) {
            events.add(new StreamEvent("delta", null, true, text));
            buffer.append(text);
        }

        /**
         * 错误帧。
         *
         * <p>真实项目在这里推 {@code event: error} + 一个 JSON 帧即可，字段就是这四个；
         * 示例把 message 和 hint 拼成一句放进 {@code text}，好让 curl 一眼看懂
         * （{@code success} 借位表示 retryable，只是示例的压缩表示，不要照抄这个映射）。</p>
         */
        @Override
        public void error(String code, String message, boolean retryable, String hint) {
            events.add(new StreamEvent("error", code, retryable,
                    hint == null || hint.isBlank() ? message : message + "；" + hint));
        }

        boolean hasText() {
            return !buffer.isEmpty();
        }

        String text() {
            return buffer.toString();
        }

        List<AgentResult.ToolEvent> toolEvents() {
            return List.copyOf(tools);
        }

        List<StreamEvent> drain() {
            return List.copyOf(events);
        }
    }
}
