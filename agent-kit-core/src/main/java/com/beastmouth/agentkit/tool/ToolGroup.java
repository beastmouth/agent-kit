package com.beastmouth.agentkit.tool;

import java.util.List;

/**
 * 一组同领域的工具。
 *
 * <p>把「一个领域 6 个工具」聚在一个类里，避免为每个动作单开一个 Bean；
 * 注册表会把它展开成独立的 {@link AgentTool}。新领域（预算、报表…）实现本接口即可。</p>
 */
public interface ToolGroup {

    /** 该领域下的全部工具 */
    List<AgentTool> tools();
}
