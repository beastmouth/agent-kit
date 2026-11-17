package com.beastmouth.agentkit.example.mock;

import com.beastmouth.agentkit.support.Json;
import com.beastmouth.agentkit.tool.AgentTool;
import com.beastmouth.agentkit.tool.ToolGroup;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 假业务工具组：天气与时间。
 *
 * <p>存在意义有两个：</p>
 * <ul>
 *   <li>演示<b>多工具组并存</b>——两个 {@link ToolGroup} 各自展开，谁都不需要知道对方；</li>
 *   <li>演示 {@code rows} 的三种取值：查到数据（3）、没查到（0）、不产出条数（null）。
 *       最后一个尤其容易被写成 0，一写就把「当前时间」误判成「空结果」。</li>
 * </ul>
 *
 * <p>数据是写死的。示例的核心不是天气，是「工具长什么样、返回值怎么填」。</p>
 */
@Component
public class MockWeatherTools implements ToolGroup {

    /** 假数据源：城市 → 天气。真实项目这里换成一个 HTTP/SDK 调用 */
    private static final Map<String, Map<String, Object>> WEATHER = Map.of(
            "北京", Map.of("weather", "晴", "temperature", 12, "wind", "北风 3 级"),
            "上海", Map.of("weather", "多云", "temperature", 18, "wind", "东风 2 级"),
            "深圳", Map.of("weather", "小雨", "temperature", 24, "wind", "南风 1 级")
    );

    @Override
    public List<AgentTool> tools() {
        return List.of(new QueryTool(), new TimeTool());
    }

    /** 查天气 */
    private class QueryTool implements AgentTool {

        @Override
        public String name() {
            return "weather_query";
        }

        @Override
        public String description() {
            return "查询某个城市当前天气。用户问「今天冷不冷 / 要带伞吗 / 某地天气」时调用";
        }

        @Override
        public String parametersSchema() {
            return """
                    {"type":"object","properties":{
                      "city":{"type":"string","description":"城市名，例如 北京"}
                    },"required":["city"]}""";
        }

        @Override
        public ToolOutcome execute(Map<String, Object> args) {
            Object raw = args == null ? null : args.get("city");
            String city = raw == null ? "" : raw.toString().trim();
            if (city.isEmpty()) {
                return ToolOutcome.fail("没给城市名，请让用户说明要查哪个城市");
            }
            Map<String, Object> data = WEATHER.get(city);
            if (data == null) {
                // 查不到 ≠ 失败。业务上是"成功执行，但没有这个城市的数据"，
                // 所以 success=true、rows=0，让模型如实说"没有该城市数据"而不是编一个温度
                return ToolOutcome.ok("暂不支持「" + city + "」的天气数据", "[]", 0);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("city", city);
            row.putAll(data);
            String summary = city + "当前" + data.get("weather") + "，" + data.get("temperature") + "℃，"
                    + data.get("wind");
            return ToolOutcome.ok(summary, Json.toJson(row), 1);
        }
    }

    /** 当前时间：演示「不产出条数」的工具 */
    private class TimeTool implements AgentTool {

        private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        @Override
        public String name() {
            return "time_now";
        }

        @Override
        public String description() {
            return "获取服务器当前时间。用户问「现在几点 / 今天几号」时调用；不要用它推断日期之外的业务数据";
        }

        @Override
        public String parametersSchema() {
            return """
                    {"type":"object","properties":{},"required":[]}""";
        }

        @Override
        public ToolOutcome execute(Map<String, Object> args) {
            String now = LocalDateTime.now().format(FMT);
            // rows 传 null：这个工具不"返回行"，传 0 会被空结果规则误伤
            return ToolOutcome.ok("现在是 " + now, Json.toJson(Map.of("now", now)), null);
        }
    }
}
