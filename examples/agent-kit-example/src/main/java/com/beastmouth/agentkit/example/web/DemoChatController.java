package com.beastmouth.agentkit.example.web;

import com.beastmouth.agentkit.agent.AgentRegistry;
import com.beastmouth.agentkit.example.support.ConversationFacade;
import com.beastmouth.agentkit.example.support.SubjectScope;
import com.beastmouth.agentkit.session.AgentSession;
import com.beastmouth.agentkit.support.AgentKitException;
import com.beastmouth.agentkit.support.ErrorCode;
import com.beastmouth.agentkit.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final com.beastmouth.agentkit.provider.ModelProviderRegistry providerRegistry;

    /** 一轮对话：会话不存在时自动新建，返回 sessionId 供下一轮续接 */
    @PostMapping("/chat")
    public ConversationFacade.Turn chat(@RequestBody ChatRequest request) {
        return facade.chat(
                // route 是演示用的「业务线」：非空即把本次请求切到 agent-kit.routes.<route>
                SubjectScope.fromHeader(request.subjectId(), request.role(), "web", request.route()),
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
        // 渠道排障用：默认走哪个、配了哪些业务路由、哪些渠道注册进来了。
        // 「路由键写错但没报错」只能靠这个接口发现——未配置的键会静默回落默认渠道
        body.put("modelProvider", providerRegistry.description());
        body.put("modelRoutes", providerRegistry.routeKeys());
        body.put("providers", providerRegistry.availableIds());
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

    /**
     * 请求体：subjectId 相当于登录用户，示例里由客户端自报。
     *
     * @param route 可选，模型路由键（对应 {@code agent-kit.routes.<route>}）；
     *              空表示走默认渠道。真实项目不要从客户端取这个值
     */
    public record ChatRequest(String subjectId, String role, Long sessionId, String message, String route) {
    }

    /** 反馈请求：rating 只有 UP / DOWN */
    public record FeedbackRequest(String subjectId, Long sessionId, String rating, String tags, String comment) {
    }

    /** 业务异常直接转成 4xx + 可读原因，不要把堆栈甩给前端 */
    @ExceptionHandler(AgentKitException.class)
    public ResponseEntity<Map<String, Object>> onAgentKitException(AgentKitException e) {
        ErrorCode code = e.errorCode() == null ? ErrorCode.INVALID_CONFIG : e.errorCode();
        Map<String, Object> body = new LinkedHashMap<>();
        // 四个字段是为前端一次到位设计的：code 做分支、message 给人看、
        // retryable 决定要不要显示「重试」、hint 决定按钮旁边写什么
        body.put("code", code.code());
        body.put("message", e.getMessage());
        body.put("retryable", code.retryable());
        body.put("hint", code.hint());
        return ResponseEntity.status(statusOf(code)).body(body);
    }

    /**
     * 错误码 → HTTP 状态。
     *
     * <p>以前一律 400，导致「模型限流」和「会话不存在」在监控里长得一模一样，
     * 告警分不清是上游抖动还是业务参数错。现在按「谁能处理」分开：
     * 503 上游、429 用户太急、500 服务端配置、400 请求本身有问题。</p>
     */
    private static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case MODEL_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case SESSION_BUSY, SESSION_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case AGENT_DISABLED, INVALID_CONFIG -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    /**
     * 未预料的异常：示例里返回 500 + 一句人话。
     *
     * <p>刻意<b>不回</b> {@code e.getMessage()}：那通常是空指针的类名或 SQL 片段，
     * 用户看不懂、攻击者爱看。详细信息只进日志。</p>
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> onRuntimeException(RuntimeException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INTERNAL_ERROR");
        body.put("message", "服务出了一点问题，正在处理");
        body.put("retryable", true);
        body.put("hint", "稍后重试一次；反复出现请把时间点告诉管理员，方便按日志排查");
        return body;
    }
}
