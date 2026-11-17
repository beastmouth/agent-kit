package com.beastmouth.agentkit.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent 注册表：容器启动时收集所有 {@link Agent}。
 *
 * <p>主 Agent 的路由提示词由这里生成，因此「新增一个子 Agent」= 写一个 Bean，
 * 路由能力自动获得，不需要改主流程代码。</p>
 */
@Slf4j
@Component
public class AgentRegistry {

    private final Map<String, Agent> agents = new LinkedHashMap<>();

    public AgentRegistry(List<Agent> beans) {
        beans.forEach(agent -> agents.put(agent.name(), agent));
        log.info("AgentRegistry#init#已注册子Agent={}", agents.keySet());
    }

    public Agent find(String name) {
        return agents.get(name);
    }

    public List<Agent> all() {
        return List.copyOf(agents.values());
    }

    /** 生成给主 Agent 选择用的清单 */
    public String routingMenu() {
        StringBuilder sb = new StringBuilder();
        agents.values().forEach(agent -> sb.append("- ").append(agent.name()).append(": ")
                .append(agent.description()).append('\n'));
        return sb.toString();
    }

    public boolean isEmpty() {
        return agents.isEmpty();
    }
}
