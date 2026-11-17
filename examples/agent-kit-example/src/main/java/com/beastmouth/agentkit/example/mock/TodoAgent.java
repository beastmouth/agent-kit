package com.beastmouth.agentkit.example.mock;

import com.beastmouth.agentkit.agent.Agent;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 假业务子 Agent：待办助手。
 *
 * <p>一个子 Agent 就三样东西：<b>名字</b>（也是落库的 {@code agent_name}、路由菜单的一行）、
 * <b>路由描述</b>（主 Agent 靠它决定派给谁）、<b>工具白名单</b> + 系统提示词。</p>
 *
 * <p>写提示词的三个纪律（示例里都体现了）：</p>
 * <ul>
 *   <li>不写工具清单——运行器会按白名单自动拼，手写必然与代码不一致；</li>
 *   <li>不写输出协议（JSON、reply/tools 字段）——协议由运行器统一追加，每个 Agent 各写一套就会互相打架；</li>
 *   <li>只写「你这个域的业务口径」：能做什么、不能做什么、含糊时怎么办。</li>
 * </ul>
 */
@Component
public class TodoAgent implements Agent {

    @Override
    public String name() {
        return "todo";
    }

    @Override
    public String description() {
        return "待办的登记、查看与完成：记一件事、我还有什么没做、把某条标记完成";
    }

    @Override
    public Set<String> toolNames() {
        // 白名单 = 隔离。天气工具在这里拿不到，模型连它的名字都看不见
        return Set.of("todo_add", "todo_list", "todo_done");
    }

    @Override
    public String systemPrompt() {
        return """
                你是待办助手，负责帮用户记录和整理待办事项。

                工作口径：
                1. 用户说「记一下 / 提醒我 / 别忘了」就是登记，把关键信息（做什么、什么时候）保留进待办内容，
                   不要自行简化成「一件事」。
                2. 用户问「还有什么没做」先查再答，按编号列出，不要凭记忆复述历史对话里的旧列表。
                3. 标记完成必须拿到明确编号；用户只说「把那个做完了」而上下文有多条候选时，先列出来问是哪一个。
                4. 查到的清单为空就直说「目前没有未完成的待办」，不要编造条目。
                5. 写操作成功后用一句话确认结果；失败就如实说明原因，不要重试同一个参数。
                """;
    }
}
