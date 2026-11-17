package com.beastmouth.agentkit.starter;

import com.beastmouth.agentkit.agent.Agent;
import com.beastmouth.agentkit.agent.AgentRegistry;
import com.beastmouth.agentkit.agent.AgentResult;
import com.beastmouth.agentkit.agent.ChatSink;
import com.beastmouth.agentkit.agent.IntentRouter;
import com.beastmouth.agentkit.agent.MainAgent;
import com.beastmouth.agentkit.agent.StreamFailure;
import com.beastmouth.agentkit.agent.SubAgentRunner;
import com.beastmouth.agentkit.support.AgentKitException;
import com.beastmouth.agentkit.support.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流式中途失败的收尾。
 *
 * <p>这些用例钉的是同一个判断：<b>正文有没有已经发出去</b>。发过就只能推错误帧收尾
 * （连接早已是 200 + SSE），没发过才允许上抛。判据写错会导致两种相反的故障：
 * 该收尾的地方断连（用户看到半句话、刷新后消失），或该报错的地方假装成功
 * （库里存了一条空回答，报表里算成功）。</p>
 */
class StreamFailureTest {

    @Test
    @DisplayName("错误码优先取异常自带的：会话类错误不能被说成模型不可用")
    void keepsOwnErrorCode() {
        assertEquals(ErrorCode.SESSION_LIMIT,
                StreamFailure.resolve(AgentKitException.of(ErrorCode.SESSION_LIMIT)));
    }

    @Test
    @DisplayName("带 AgentKitException 的传输错误保留原码，不按类名猜测")
    void wrappedTransportKeepsCode() {
        AgentKitException e = AgentKitException.of(ErrorCode.TOOL_FAILED, "工具挂了", new IOException("reset"));
        assertEquals(ErrorCode.TOOL_FAILED, StreamFailure.resolve(e));
    }

    @Test
    @DisplayName("裸 IOException 归为模型不可用（可重试）")
    void bareIOExceptionIsModelUnavailable() {
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, StreamFailure.resolve(new IOException("connection reset")));
        assertTrue(ErrorCode.MODEL_UNAVAILABLE.retryable());
    }

    @Test
    @DisplayName("包装了两层的传输异常也能识别：框架异常几乎都是包装类")
    void detectsNestedTransportFailure() {
        RuntimeException wrapped = new IllegalStateException("call failed",
                new RuntimeException("io", new java.util.concurrent.TimeoutException("read timed out")));
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, StreamFailure.resolve(wrapped));
    }

    @Test
    @DisplayName("空指针不算模型不可用：把 NPE 说成渠道故障会让人查错方向")
    void npeIsInternalError() {
        assertEquals(ErrorCode.INTERNAL_ERROR, StreamFailure.resolve(new NullPointerException("x")));
    }

    @Test
    @DisplayName("已经输出过正文时：推一帧 error，并原样返回已产出的正文与动作")
    void partialKeepsStreamedText() {
        Recorder sink = new Recorder();
        AgentResult.ToolEvent event = new AgentResult.ToolEvent("todo_add", true, "已记录", 1);

        AgentResult result = StreamFailure.partial("todo", "已经记下了，另外", List.of(event), sink,
                new IOException("socket closed"));

        assertEquals("已经记下了，另外", result.reply(), "已流出去的正文不能丢，库里要和用户看到的一致");
        assertEquals(1, result.events().size(), "失败前完成的工具动作要保留，质量规则还要用");
        assertTrue(result.partial(), "半截结果必须自带标记：否则外层会把它当成功记进 ai_run，成功率虚高");
        assertEquals(1, sink.errors.size(), "必须推错误帧，否则前端只能显示「网络错误」");
        assertEquals(ErrorCode.MODEL_UNAVAILABLE.code(), sink.errors.get(0)[0]);
        assertTrue((Boolean) sink.errors.get(0)[2], "传输类错误可重试，前端要显示重试按钮");
    }

    @Test
    @DisplayName("错误帧自身推不出去时只记日志：不能把一次部分成功变成两次失败")
    void errorFrameFailureDoesNotPropagate() {
        ChatSink broken = new ChatSink() {
            @Override
            public void status(String stage, String label) {
            }

            @Override
            public void tool(String name, boolean success, String summary) {
            }

            @Override
            public void delta(String text) {
            }

            @Override
            public void error(String code, String message, boolean retryable, String hint) {
                throw new IllegalStateException("连接已断开");
            }
        };

        assertDoesNotThrow(() -> StreamFailure.report(broken, new IOException("eof")));
        assertDoesNotThrow(() -> StreamFailure.partial("a", "正文", List.of(), broken, new IOException("eof")));
    }

    @Test
    @DisplayName("只实现三个方法的旧 sink 仍可编译运行：error 是 default，升级不要求改业务代码")
    void legacySinkStillWorks() {
        List<String> deltas = new ArrayList<>();
        ChatSink legacy = new ChatSink() {
            @Override
            public void status(String stage, String label) {
            }

            @Override
            public void tool(String name, boolean success, String summary) {
            }

            @Override
            public void delta(String text) {
                deltas.add(text);
            }
        };

        assertDoesNotThrow(() -> legacy.error(ErrorCode.MODEL_UNAVAILABLE));
        assertDoesNotThrow(() -> legacy.error("X", "x", true, "y"));
        assertEquals(List.of(), deltas, "默认实现不能顺带往 delta 里塞错误文本：前端会把错误渲染进气泡");
    }

    // ------------------------------------------------------------------
    // MainAgent：多子 Agent 串行时，第 N 个失败要保留前 N-1 段正文
    // ------------------------------------------------------------------

    @Test
    @DisplayName("第二个子 Agent 失败时：保留第一段正文 + 推错误帧，不上抛")
    void secondAgentFailureKeepsFirstReply() {
        Agent first = agent("todo");
        Agent second = agent("weather");
        SubAgentRunner runner = mock(SubAgentRunner.class);
        when(runner.run(eq(first), anyList(), anyString(), anyList(), any(ChatSink.class)))
                .thenReturn(new AgentResult("todo", "已经记下了", List.of()));
        when(runner.run(eq(second), anyList(), anyString(), anyList(), any(ChatSink.class)))
                .thenThrow(AgentKitException.of(ErrorCode.MODEL_UNAVAILABLE));

        Recorder sink = new Recorder();
        MainAgent main = mainAgent(List.of(first, second), runner);

        AgentResult result = main.handle(List.of(), "记一下顺便看天气", null, List.of(), sink);

        assertEquals("已经记下了", result.reply(), "前面已经流给用户的正文必须作为结果返回，否则库里什么都没有");
        assertEquals(1, sink.errors.size());
    }

    @Test
    @DisplayName("第一个子 Agent 就失败：一个字都没输出，必须上抛交给异常处理器")
    void firstAgentFailurePropagates() {
        Agent first = agent("todo");
        SubAgentRunner runner = mock(SubAgentRunner.class);
        when(runner.run(eq(first), anyList(), anyString(), anyList(), any(ChatSink.class)))
                .thenThrow(AgentKitException.of(ErrorCode.MODEL_UNAVAILABLE));

        Recorder sink = new Recorder();
        MainAgent main = mainAgent(List.of(first), runner);

        AgentKitException e = assertThrows(AgentKitException.class,
                () -> main.handle(List.of(), "记一下", null, List.of(), sink));
        assertSame(ErrorCode.MODEL_UNAVAILABLE, e.errorCode());
        assertTrue(sink.errors.isEmpty(), "还没输出任何正文，错误交给 Controller 转响应体，不该再推帧");
    }

    // ------------------------------------------------------------------

    private static MainAgent mainAgent(List<Agent> agents, SubAgentRunner runner) {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.isEmpty()).thenReturn(false);
        IntentRouter router = mock(IntentRouter.class);
        when(router.route(anyList(), anyString(), any(), any(ChatSink.class)))
                .thenReturn(new IntentRouter.Route(agents, null));
        return new MainAgent(registry, runner, router);
    }

    private static Agent agent(String name) {
        Agent agent = mock(Agent.class);
        when(agent.name()).thenReturn(name);
        when(agent.description()).thenReturn(name + " agent");
        return agent;
    }

    /** 记录四类事件；error 存成 [code, message, retryable, hint] */
    private static final class Recorder implements ChatSink {

        private final List<String> deltas = new ArrayList<>();
        private final List<Object[]> errors = new ArrayList<>();

        @Override
        public void status(String stage, String label) {
        }

        @Override
        public void tool(String name, boolean success, String summary) {
        }

        @Override
        public void delta(String text) {
            deltas.add(text);
        }

        @Override
        public void error(String code, String message, boolean retryable, String hint) {
            errors.add(new Object[]{code, message, retryable, hint});
        }
    }
}
