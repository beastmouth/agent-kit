package com.beastmouth.agentkit.example.mock;

import com.beastmouth.agentkit.agent.Agent;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 假业务子 Agent：天气与时间。
 *
 * <p>和 {@link TodoAgent} 一起演示<b>两个子 Agent 的隔离与路由</b>：两个 Agent 的工具白名单不重叠，
 * 主 Agent 只看到两行 description，用户问「上海今天冷不冷」就该派到这里。</p>
 *
 * <p>第二个 Agent 的存在还顺带演示一件事：<b>新增能力不需要改任何框架代码</b>。
 * 写这个类 + 上面那组工具，重启即生效——路由菜单、提示词、指标标签都会自动带上。</p>
 */
@Component
public class WeatherAgent implements Agent {

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public String description() {
        return "天气与当前时间：某地天气、冷不冷、要不要带伞、现在几点";
    }

    @Override
    public Set<String> toolNames() {
        return Set.of("weather_query", "time_now");
    }

    @Override
    public String systemPrompt() {
        return """
                你是天气与时间助手，只回答这两类问题。

                工作口径：
                1. 问天气必须先查再答，不要用常识推测温度；工具说没有该城市数据，就告诉用户"暂不支持这个城市"，
                   不要拿邻近城市的数据顶替。
                2. 用户问「要带伞吗」这类需要判断的问题，先查天气再给结论，结论里带上温度和天气现象。
                3. 问时间直接用 time_now，不要自己推算日期，也不要用它推断业务数据的时间范围。
                4. 超出天气与时间的提问（记账、翻译、写代码），说明这不是你的范围，让用户换个问题。
                """;
    }
}
