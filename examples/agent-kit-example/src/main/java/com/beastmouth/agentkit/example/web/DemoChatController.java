package com.beastmouth.agentkit.example.web;

import com.beastmouth.agentkit.agent.AgentRegistry;
import com.beastmouth.agentkit.example.support.ConversationFacade;
import com.beastmouth.agentkit.example.support.SubjectScope;
import com.beastmouth.agentkit.session.AgentSession;
import com.beastmouth.agentkit.support.AgentKitException;
import com.beastmouth.agentkit.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 示例入口：把 HTTP 请求翻译成 {@link ConversationFacade} 的调用。
 *
 * <p>控制器只做三件事：<b>取调用方 → 交给 Facade → 把结果转成 JSON</b>。
 * 一旦控制器里出现「判断权限」「拼历史」「写消息」，说明业务逻辑漏到了不该在的层。</p>
 *
 * <p><b>鉴权是假的</b>：这里直接读 {@code X-Subject-Id} / {@code X-Subject-Role} 请求头。
 * 真实项目必须换成从 JWT / Session 解析，绝不能让客户端自报身份——那等于把越权接口
 * 摆到公网上。示例这么写只是为了 curl 能一行跑通。</p>
 */
@RestController
@RequestMapping("/demo")
@RequiredArgsConstructor
public class DemoChatController {

    private final ConversationFacade facade;
    private final AgentRegistry agentRegistry;
    private final ToolRegistry toolRegistry;

    /** 一轮对话：会话不存在时自动新建，返回 sessionId 供下一轮续接 */
    @PostMapping("/chat")
    public ConversationFacade.Turn chat(@RequestBody ChatRequest request) {
        return facade.chat(
                SubjectScope.fromHeader(request.subjectId(), request.role(), "web"),
                request.sessionId(),
                request.message());
    }

    /** 我的会话列表 */
    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions(@RequestParam("subjectId") String subjectId) {
        return facade.sessions(subjectId).stream().map(this::toView).toList();
    }

    /** 提交反馈：前端只知道"刚才那句话"，runId 由服务端按会话倒查 */
    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestBody FeedbackRequest request) {
        Long runId = facade.feedback(request.subjectId(), request.sessionId(),
                request.rating(), request.tags(), request.comment());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("accepted", runId != null);
        return body;
    }

    /**
     * 自检：当前装配了哪些子 Agent、哪些工具。
     *
     * <p>新接一个业务时先打这个接口——「工具注册了但 Agent 白名单里没写」和
     * 「Agent 写了但没被扫到」都会在这里暴露，比对着日志猜快得多。</p>
     */
    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agents", agentRegistry.all().stream().map(agent -> Map.of(
                "name", agent.name(),
                "description", agent.description(),
                "tools", agent.toolNames().stream().sorted().toList()
        )).toList());
        body.put("allTools", toolRegistry.allNames().stream().sorted().toList());
        return body;
    }

    private Map<String, Object> toView(AgentSession session) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("sessionId", session.getId());
        view.put("title", session.getTitle());
        view.put("channel", session.getChannel());
        view.put("lastAgent", session.getAgentName());
        view.put("messageCount", session.getMessageCount());
        view.put("lastMessageAt", session.getLastMessageAt());
        return view;
    }

    /** 请求体：subjectId 相当于登录用户，示例里由客户端自报 */
    public record ChatRequest(String subjectId, String role, Long sessionId, String message) {
    }

    /** 反馈请求：rating 只有 UP / DOWN */
    public record FeedbackRequest(String subjectId, Long sessionId, String rating, String tags, String comment) {
    }

    /** 业务异常直接转成 4xx + 可读原因，不要把堆栈甩给前端 */
    @ExceptionHandler(AgentKitException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> onAgentKitException(AgentKitException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.errorCode() == null ? "ERROR" : e.errorCode().name());
        body.put("message", e.getMessage());
        return body;
    }

    /** 未预料的异常：示例里返回 500 并留一句提示；真实项目交给全局异常处理器 */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> onRuntimeException(RuntimeException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INTERNAL_ERROR");
        body.put("message", e.getMessage());
        return body;
    }
}
