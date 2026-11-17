package com.beastmouth.agentkit.example.support;

import com.beastmouth.agentkit.context.RunContext;
import com.beastmouth.agentkit.tool.AgentTool;
import com.beastmouth.agentkit.tool.ToolInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 「这次调用准不准」这一层适配：工具准入。
 *
 * <p>脚手架<b>不内置任何权限模型</b>，因为它猜不到你的判定依据是角色表、工单状态、
 * 套餐配额还是灰度名单。它只提供「工具执行前 / 后」两个时机，判定逻辑全部由业务实现。</p>
 *
 * <h3>示例规则</h3>
 * <p>调用方标签 {@code role=viewer} 时禁止写操作（本示例把 {@code todo_add} / {@code todo_done}
 * 视为写）。规则本身很傻——重点是那份<b>形状</b>：</p>
 * <ul>
 *   <li>返回 {@code null} 放行，返回非空字符串拒绝，该字符串会回灌给模型；</li>
 *   <li>拒绝语要写成「模型能转述给用户」的话，而不是内部错误码；</li>
 *   <li><b>永不抛异常</b>：运行器虽然兜底（异常按放行处理），但一个判定逻辑的 NPE
 *       换来的是一次静默越权，不如自己 catch 掉。</li>
 * </ul>
 *
 * <p>真实项目里通常会有多个 {@code ToolInterceptor}（鉴权、审计、限流、脱敏），
 * 靠 {@code @Order} 决定顺序：数值小的先跑，任何一个拒绝就短路，后面的不再执行。</p>
 */
@Slf4j
@Component
@Order(100)
public class DemoToolInterceptor implements ToolInterceptor {

    /** 示例：需要写权限的工具。真实项目通常从注解或配置表生成，而不是硬编码 */
    private static final Set<String> WRITE_TOOLS = Set.of("todo_add", "todo_done");

    @Override
    public String before(String toolName, Map<String, Object> args) {
        try {
            Object role = RunContext.label(SubjectScope.LABEL_ROLE);
            if ("viewer".equals(role) && WRITE_TOOLS.contains(toolName)) {
                // 这句话是给模型看的，模型会转述给用户；不要在这里写 "403" 或堆栈
                return "当前账号是只读权限，不能修改待办（需要 owner 权限）";
            }
            return null;
        } catch (RuntimeException e) {
            // 判定逻辑自己出错时选择放行：拦住会把一次内部 bug 变成用户看到的"没权限"
            log.warn("DemoToolInterceptor#before#判定异常，按放行处理 tool={} err={}", toolName, e.toString());
            return null;
        }
    }

    @Override
    public void after(String toolName, Map<String, Object> args, AgentTool.ToolOutcome outcome, long durationMs) {
        // 被拦截时 durationMs=0 且 outcome.success=false，可据此区分"拦下"与"跑失败"
        log.info("DemoToolInterceptor#after#subject={} tool={} ok={} rows={} cost={}ms",
                RunContext.subjectId(), toolName, outcome != null && outcome.success(),
                outcome == null ? null : outcome.rows(), durationMs);
    }
}
