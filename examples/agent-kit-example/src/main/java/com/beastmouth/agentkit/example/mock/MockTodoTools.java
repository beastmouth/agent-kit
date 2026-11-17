package com.beastmouth.agentkit.example.mock;

import com.beastmouth.agentkit.context.RunContext;
import com.beastmouth.agentkit.support.Json;
import com.beastmouth.agentkit.tool.AgentTool;
import com.beastmouth.agentkit.tool.ToolGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假业务工具组：待办清单。
 *
 * <p>这里是「业务侧要写的东西」的最小形态：一个 {@link ToolGroup} 包住一个领域的三个动作，
 * 容器启动时被 {@code ToolRegistry} 自动展开成独立工具，不需要注册表、不需要配置文件。</p>
 *
 * <h3>三个要点，抄的时候别漏</h3>
 * <ol>
 *   <li><b>隔离靠 {@link RunContext#subjectId()}</b>：工具被模型调用时没有方法参数能带调用方，
 *       只能从当前上下文取。示例用调用方标识做数据分区，真实项目里换成你自己的用户/租户过滤条件。</li>
 *   <li><b>{@code rows} 必须如实报</b>：查询类传实际命中条数（0 就是 0，这正是幻觉检测要抓的信号），
 *       写入类传影响行数，纯计算/无关的传 {@code null}。</li>
 *   <li><b>失败返回 {@code fail}，不要抛异常</b>：抛异常会被运行器包成一句「执行失败」，
 *       而 {@code fail} 的 summary 会原样回灌给模型，模型能据此调整措辞。</li>
 * </ol>
 *
 * <p>内存 Map 只是为了让示例零建表可跑。真实实现里这就是一个 Service 调用。</p>
 */
@Slf4j
@Component
public class MockTodoTools implements ToolGroup {

    /** key = 调用方标识，value = 该调用方的待办表。真实项目换成数据库查询条件 */
    private final Map<String, Map<Integer, TodoItem>> store = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public List<AgentTool> tools() {
        return List.of(new AddTool(), new ListTool(), new DoneTool());
    }

    /** 一条待办 */
    private record TodoItem(int id, String title, boolean done) {
    }

    /** 取当前调用方的待办表；无标识时归到 anonymous，保证工具永远能跑 */
    private Map<Integer, TodoItem> bucket() {
        String subject = RunContext.subjectId();
        return store.computeIfAbsent(subject == null ? "anonymous" : subject, key -> new ConcurrentHashMap<>());
    }

    /** 新增一条待办 */
    private class AddTool implements AgentTool {

        @Override
        public String name() {
            return "todo_add";
        }

        @Override
        public String description() {
            return "新增一条待办。用户说「记一下 / 提醒我 / 别忘了」时调用，参数是待办内容原文";
        }

        @Override
        public String parametersSchema() {
            return """
                    {"type":"object","properties":{
                      "title":{"type":"string","description":"待办内容，保留用户原话里的关键信息与时间"}
                    },"required":["title"]}""";
        }

        @Override
        public ToolOutcome execute(Map<String, Object> args) {
            Object raw = args == null ? null : args.get("title");
            String title = raw == null ? "" : raw.toString().trim();
            if (title.isEmpty()) {
                return ToolOutcome.fail("待办内容为空，请告诉用户需要记什么");
            }
            Map<Integer, TodoItem> mine = bucket();
            int id = sequence.incrementAndGet();
            mine.put(id, new TodoItem(id, title, false));
            log.info("MockTodoTools#add#subject={} id={} title={}", RunContext.subjectId(), id, title);
            // 写入类：rows 传影响行数（这里是 1），不是 null
            return ToolOutcome.ok("已记下：" + title, Json.toJson(Map.of("id", id, "title", title)), 1);
        }
    }

    /** 列出未完成待办 */
    private class ListTool implements AgentTool {

        @Override
        public String name() {
            return "todo_list";
        }

        @Override
        public String description() {
            return "列出当前用户还没做完的待办。用户问「我还有什么没做 / 待办有哪些」时调用";
        }

        @Override
        public String parametersSchema() {
            return """
                    {"type":"object","properties":{},"required":[]}""";
        }

        @Override
        public ToolOutcome execute(Map<String, Object> args) {
            List<TodoItem> open = new ArrayList<>();
            bucket().values().stream()
                    .filter(item -> !item.done())
                    .sorted(Comparator.comparingInt(TodoItem::id))
                    .forEach(open::add);
            if (open.isEmpty()) {
                // 查询类：0 条就报 0。别用 ok(summary, data) 那个重载——它写的是 null，
                // 会被质量规则当成"该工具不产出条数"，空结果信号就丢了
                return ToolOutcome.ok("没有未完成的待办", "[]", 0);
            }
            String summary = "共 " + open.size() + " 条未完成：" + open.stream()
                    .map(item -> "#" + item.id() + " " + item.title())
                    .reduce((a, b) -> a + "；" + b)
                    .orElse("");
            List<Map<String, Object>> rows = new ArrayList<>();
            open.forEach(item -> rows.add(Map.of("id", item.id(), "title", item.title())));
            return ToolOutcome.ok(summary, Json.toJson(rows), open.size());
        }
    }

    /** 把一条待办标记完成 */
    private class DoneTool implements AgentTool {

        @Override
        public String name() {
            return "todo_done";
        }

        @Override
        public String description() {
            return "把一条待办标记为已完成。用户说「做完了 / 划掉 #3」时调用，参数取自 todo_list 返回的 id";
        }

        @Override
        public String parametersSchema() {
            return """
                    {"type":"object","properties":{
                      "id":{"type":"integer","description":"待办编号，来自 todo_list 的结果"}
                    },"required":["id"]}""";
        }

        @Override
        public ToolOutcome execute(Map<String, Object> args) {
            Object raw = args == null ? null : args.get("id");
            int id;
            try {
                id = Integer.parseInt(String.valueOf(raw).trim());
            } catch (RuntimeException e) {
                return ToolOutcome.fail("没认出这是哪条待办，请先列出待办让用户确认编号");
            }
            Map<Integer, TodoItem> mine = bucket();
            TodoItem item = mine.get(id);
            if (item == null) {
                // 找不到就如实说，不要"猜一个"标记掉——模型会照着 summary 编造"已完成"
                return ToolOutcome.fail("没有编号为 #" + id + " 的待办，请先列出待办确认");
            }
            mine.put(id, new TodoItem(item.id(), item.title(), true));
            return ToolOutcome.ok("已完成 #" + id + " " + item.title(), null, 1);
        }
    }

    /** 仅供示例观察内存状态，业务代码不需要 */
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        store.forEach((subject, items) -> result.put(subject, items.size()));
        return result;
    }
}
