# 示例：一个假业务怎么接进 agent-kit

这个目录是一个**能跑起来的假业务**，只做两件事：待办清单和天气查询。天气是写死的，
待办存在内存里，没有任何外部 API —— 目的是把「接入脚手架要写什么」压到最小，
剩下的都是模板。

**它不在脚手架里，也不参与默认构建**：

```bash
mvn clean package              # 只构建 core / memory / starter
mvn -Pexamples clean package   # 连这个示例一起构建
```

示例 pom 里设了 `maven.install.skip` / `maven.deploy.skip`，永远不会被发布到仓库。
真实项目里把这一层照抄一遍、换成自己的业务即可。

---

## 一、按接入顺序看文件

| # | 文件 | 干什么 | 抄的时候注意 |
|---|---|---|---|
| 1 | `pom.xml` | 引 `agent-kit-starter`（+ `agent-kit-memory`） | `memory` 在 starter 里是 optional，用记忆才显式加 |
| 2 | `DemoApplication.java` | 启动类 | **`@MapperScan` 必须列出脚手架的四个 mapper 包**，否则一调会话就 NPE |
| 3 | `mock/MockTodoTools.java` | 一个领域的三个工具 | `rows` 必须如实填；失败用 `ToolOutcome.fail` 不抛异常 |
| 4 | `mock/MockWeatherTools.java` | 第二个领域的两个工具 | 演示 `rows` 三态：有数据 1 / 没数据 0 / 不产出条数 null |
| 5 | `mock/TodoAgent.java`、`mock/WeatherAgent.java` | 两个子 Agent | 提示词只写业务口径，**不写工具清单和输出协议**（运行器会拼） |
| 6 | `support/SubjectScope.java` | RunContext 适配层 | 必须 try-with-resources；`close` 里 `RunContext.clear()`，否则线程池串号 |
| 7 | `support/DemoToolInterceptor.java` | 工具准入 | 权限判定在这里做；返回字符串即拒绝，永不抛异常 |
| 8 | `support/DemoTurnHook.java` | 每轮钩子 | 「每轮一次」的东西放这里，别和工具拦截器混用 |
| 9 | `support/ConversationFacade.java` | **会话 + 记忆拼装**（核心，约 100 行） | 顺序不能改，见文件头注释 |
| 10 | `web/DemoChatController.java` | HTTP 入口 | 只做「取身份 → 交 Facade → 转 JSON」 |

**真实项目最少要写的是 2、3、4、5、6、9**；7 和 8 按需。

---

## 二、跑起来

前置：MySQL（会话与观测明细）、Redis（会话锁与缓存）。Milvus 只在打开记忆时才需要。

```bash
# 1. 建库建表
mysql -uroot -p -e "create database if not exists agent_kit_demo default charset utf8mb4"
mysql -uroot -p agent_kit_demo < ../agent-kit-starter/src/main/resources/db/agent-kit-schema.sql

# 2. 给凭据（不要写进 yml）
export MYSQL_PASSWORD='...'
export REDIS_PASSWORD='...'

# 3. 构建并起服务（默认走本机 Ollama；换 DeepSeek 改 application.yml 的 provider）
mvn -Pexamples clean install -DskipTests
java -jar examples/agent-kit-example/target/agent-kit-example-0.1.0.jar

# 换引擎跑同一份业务（pom 里已引 agent-kit-agentscope）
AGENT_KIT_ENGINE=agentscope java -jar examples/agent-kit-example/target/agent-kit-example-0.1.0.jar
```

换引擎后启动日志会多一行 `EngineGuard#check#Agent 引擎 = agentscope（AgentScopeEngine）`；
如果还打印 `classpath 上有 agent-kit-agentscope，但 ...=legacy 生效中`，说明配置没生效。
两套引擎的差异见 [`docs/08-引擎选型`](../docs/08-引擎选型.md)。

### 1）先自检装配

```bash
curl -s localhost:18080/demo/catalog | python3 -m json.tool
```

期望看到两个 Agent、五个工具。`agents[].tools` 里少一个工具，说明白名单写错了；
`allTools` 里少一个，说明那个 `ToolGroup` 没被扫到。这一步比对着日志猜快得多。

### 2）正常对话（写入）

```bash
curl -s localhost:18080/demo/chat -H 'Content-Type: application/json' -d '{
  "subjectId": "u-42", "role": "owner",
  "message": "帮我记一下周五交房租"
}' | python3 -m json.tool
```

返回里的 `sessionId` 带上去继续问，模型能看到上一轮：

```bash
curl -s localhost:18080/demo/chat -H 'Content-Type: application/json' -d '{
  "subjectId": "u-42", "role": "owner", "sessionId": 1,
  "message": "我还有什么没做"
}'
```

### 3）只读账号被拦（工具准入）

```bash
curl -s localhost:18080/demo/chat -H 'Content-Type: application/json' -d '{
  "subjectId": "u-99", "role": "viewer",
  "message": "帮我记一下明天开会"
}'
```

`DemoToolInterceptor` 会拒绝 `todo_add`，返回的字符串回灌给模型，模型会转述成
「当前账号是只读权限」而不是反复重试。日志里能看到 `工具被拦截`。

### 4）数据隔离（RunContext 生效的证据）

```bash
curl -s 'localhost:18080/demo/sessions?subjectId=u-42'
curl -s 'localhost:18080/demo/sessions?subjectId=u-99'
```

两个调用方各自只看到自己的会话与待办 —— 隔离来自 `namespace + owner` 与工具里的
`RunContext.subjectId()`，没有任何一处靠客户端传的 owner 参数做过滤。

---

## 三、六个最容易踩的坑

**1. 忘了 `RunContext.clear()`**
线程池复用后，A 的 subject 留在线程上，B 的请求直接冒用 A 的身份写数据，不报错。
只在压测时显形。`SubjectScope` 就是为这件事存在的。

**2. 把 `rows` 写成 0 或 null 搞反**
查询 0 条 → `rows=0`（这是幻觉检测最值钱的信号）；写操作 → 影响行数；
纯计算 / 打招呼 → `null`。写成 0 会被误判成「查了但没数据」。

**3. 会话读写不在同一个锁里**
两个请求同时进来，都读到同一份历史、各自追加，库里出现两条互不知情的回答。
`ConversationFacade#chat` 把「读历史 + 写用户消息 + 调模型 + 写助手消息」放在
`sessionLock.tryRun` 的同一个临界区里，就是这个原因。

**4. 让客户端传 `namespace`**
示例里 `namespace` 是配置项（`demo.namespace`），`owner` 来自鉴权结果。
如果从请求体取 `namespace`，等于把分区隔离的钥匙交给客户端。

**5. 忘了 `RunScope.bindSession(sessionId)`**
`ai_run.session_id` 会落 NULL，于是「按会话看链路」和「点踩按会话倒查 runId」
两个功能静默失效——`/demo/feedback` 永远返回 `{"accepted": false}`。
这个坑是 2026-11-17 实际跑示例时踩到的：接口不报错，只是反馈永远存不进去。

**6. 和别的系统共用 Milvus 时没改 collection 名**
`agent-kit.memory.collection` 默认 `ai_agent_memory`。同一台 Milvus 上如果已有另一套
agent 用着同名 collection，两边的记忆会互相串——而且 `rebuildFromMysql()` 会把**你自己的表**
灌进共用的集合。接生产前先改成 `<你的系统>_memory`。

---

## 四、冒烟记录（2026-11-17 本机实测）

| 用例 | 结果 |
|---|---|
| `GET /demo/catalog` | 2 个 Agent、5 个工具，白名单与注册表一致 |
| 「帮我记一下周五交房租」 | 路由到 `todo`，`todo_add` rows=1，回复「已记下」 |
| 同会话追问「我还有什么没做」 | `todo_list` rows=1，能引用上一轮记的条目（多轮历史生效） |
| `role=viewer` 写入 | 被 `DemoToolInterceptor` 拦截，模型如实转述「只读权限」，未重试 |
| 「杭州今天冷不冷」 | 路由到 `weather`，`weather_query` rows=0，答「暂不支持这个城市」，没有编温度 |
| `POST /demo/feedback` | 按会话倒查到 runId，落 `ai_feedback` |
| 观测落库 | `ai_run` / `ai_llm_call` / `ai_tool_call` / `ai_eval_score` 均有数据，`subject_id` 正确 |

观测数据核对过 `ai_run.session_id` 非空、`subject_id = u-demo-42`，说明
`RunContext` 与 `RunScope.bindSession` 两条链路都通了。

测试数据已清理：示例库 `agent_kit_demo` 已 drop、Redis `agent-kit:*` key 已删、
示例进程已停。**没有动 Milvus**——记忆开关全程关闭，避免与同实例上另一套系统的
`ai_agent_memory` collection 混写。

---

## 五、换成你自己的业务要改哪

| 现在的 | 换成 |
|---|---|
| `MockTodoTools` 里的内存 Map | 你的 Service / Mapper（事务、锁、审计照常复用） |
| `SubjectScope.fromHeader` | 从 JWT / Session / IM 回调解析出的身份 |
| `DemoToolInterceptor` 的 `role=viewer` 规则 | 你的角色表 / 工单状态 / 套餐配额 |
| `TodoAgent` / `WeatherAgent` 的提示词 | 你的领域口径（能做什么、不能做什么、含糊时怎么办） |
| `demo.namespace` | 你的租户 / 组织 / 项目键 |

其余（`ConversationFacade` 的顺序、`SubjectScope` 的 try-with-resources、
`DemoApplication` 的四个 `@MapperScan` 包）**不要改**——那是脚手架与业务之间的缝合线。
