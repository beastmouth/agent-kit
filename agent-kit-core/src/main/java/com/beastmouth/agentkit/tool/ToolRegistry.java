package com.beastmouth.agentkit.tool;

import com.beastmouth.agentkit.model.ToolSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册表。
 *
 * <p>容器启动时收集全部 {@link AgentTool}，按名字建索引。子 Agent 通过白名单（{@code toolNames}）
 * 声明自己能用哪些工具，实现「工具也是隔离的」：订单 Agent 拿不到退款域的工具。</p>
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> beans, List<ToolGroup> groups) {
        beans.forEach(tool -> tools.put(tool.name(), tool));
        groups.forEach(group -> group.tools().forEach(tool -> tools.put(tool.name(), tool)));
        log.info("ToolRegistry#init#已注册工具={}", tools.keySet());
    }

    /**
     * 按白名单取工具描述，供拼提示词。
     *
     * <p><b>必须按工具名排序输出。</b>白名单是 {@code Set}，各 Agent 又用 {@code Set.of(...)}
     * 声明，而 JDK 不可变集合带每进程随机 SALT：同一个 Jar 连启三次会给出三种迭代顺序。
     * 工具清单进系统提示词，顺序一变，模型侧的前缀缓存（DeepSeek 按前缀完全匹配）就整段作废，
     * 两个 Pod 之间也各是各的顺序。这里统一排序，让「同一份代码 → 同一段提示词」成立。</p>
     */
    public List<ToolSpec> specs(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return names.stream()
                .map(tools::get)
                .filter(java.util.Objects::nonNull)
                .map(tool -> new ToolSpec(tool.name(), tool.description(), tool.parametersSchema()))
                .sorted(java.util.Comparator.comparing(ToolSpec::name))
                .toList();
    }

    /** 取工具实例，不存在返回 null（模型可能幻觉出不存在的工具名） */
    public AgentTool find(String name) {
        return tools.get(name);
    }

    public Set<String> allNames() {
        return tools.keySet();
    }
}
