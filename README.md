# agent-kit

纯 Agent 脚手架：把「模型接入 + 多子 Agent 编排 + 工具调用 + 可观测 + 质量评测 + 长期记忆」做成可复用的底座，
**不含任何业务、权限、用户体系**。

它的目标很具体：换一个业务系统（电商客服、工单助手、内部知识问答、个人记账…）时，
只需要写「子 Agent 的提示词」和「工具实现」，其余全部复用。

📖 **完整文档在 [`docs/`](docs/README.md)**：架构设计、快速开始、会话与记忆、可观测性、质量评测、接入清单、配置参考。
🛠 **可跑示例在 [`examples/`](examples/README.md)**：假业务（待办 + 天气），四层适配层的模板。

---

## 1. 边界：故意不做什么

这是本仓库最重要的一节。以下东西**不在**脚手架里，也不打算加：

| 不做 | 为什么 | 你应该怎么做 |
|---|---|---|
| 用户表 / 登录态 | 每个系统的账号体系都不同，硬塞一个 `user_id` 只会让两边都别扭 | 用 `RunContext.Subject(id, labels)` 传一个**不透明字符串**（用户 id、工单号、设备号都行） |
| 租户 / 组织模型 | 单租户、多租户、按项目隔离的需求完全不同 | 用 `namespace` 表达分区：`tenant:1`、`org:7`、`project:abc` |
| 权限判定 | 角色表、工单状态、配额……脚手架不可能知道 | 实现 `ToolInterceptor`，在里面做你的权限检查 |
| 业务提示词 | 术语、单位、行业规范属于业务知识 | 写在各 `Agent#systemPrompt()` 与 `agent-kit.extra-instructions` |
| 业务表 | 脚手架的表全部是 `ai_*`，与业务表零外键 | 工具里用你自己的 Mapper |

一句话：**脚手架管「怎么跑 Agent」，业务管「Agent 说什么、能做什么、谁能用」。**

---

## 2. 模块

```
agent-kit
├── docs                # 核心文档：架构、快速开始、可观测、评测、接入清单、配置
├── agent-kit-core      # 契约与运行时：模型、工具、Agent、观测、评测（无 DB 依赖也能编译）
├── agent-kit-memory    # 可选：长期记忆（Milvus 向量）与会话上下文（Redis 缓存 + 分布式锁）
├── agent-kit-starter   # Spring Boot Starter：引入即装配 + 建表脚本 + 配置示例
└── examples
    └── agent-kit-example  # 参考实现：待办 + 天气，演示 Agent/Tool/RunContext/会话与记忆
```

`examples/` **不在默认构建里**：它是给接入方看的参考，不是脚手架的发布物。

```bash
mvn clean package              # 只构建 core / memory / starter
mvn -Pexamples clean package   # 连示例一起构建
```

示例 pom 里已设 `maven.install.skip` / `maven.deploy.skip`，永远不会被发布。

包结构（`com.beastmouth.agentkit`）：

| 包 | 内容 |
|---|---|
| `agent` | `ChatEngine` 契约、`MainAgent`（自研引擎）、`SubAgentRunner`、`IntentRouter`、`AgentRegistry`、`PromptFragments` |
| `tool` | `AgentTool`、`FunctionTool`、`ToolGroup`、`ToolRegistry`、`ToolInterceptor` |
| `provider` | `ModelProvider` 契约 + DeepSeek / SiliconFlow / Ollama / OpenAI 兼容实现 + `FailoverModelProvider` |
| `obs` | `RunScope`、`ObsWriter`、`AiMetrics`、`AiTracing`、`CostCalculator`、`ai_*` 三张观测表的实体与 Mapper |
| `eval` | 确定性质量规则、`JudgeService`（LLM 评审）、`GoldenSetRunner`（离线回归）、`FeedbackService` |
| `context` | `RunContext`（调用方标识）、`TurnHook`（每轮钩子） |
| `config` | `AiProperties`、`ObsProperties`、`EvalProperties`、`RouterProperties` |
| `memory` | `AgentMemoryService`（提炼/检索/重建）、`EmbeddingClient`、`MilvusVectorStore` |
| `session` | `AgentSessionStore`、`AgentContextCache`、`AgentSessionLock`、`SessionSourceRegistry` |
| `support` | `AgentKitException`、`ErrorCode`、`Json`、`BaseEntity` |

---

## 3. 快速开始

### 3.1 依赖

```xml
<dependency>
    <groupId>com.beastmouth.kit</groupId>
    <artifactId>agent-kit-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

组件扫描由 starter 自动完成（`AgentKitAutoConfiguration`），不需要在你的启动类上加 `scanBasePackages`。

### 3.2 建表

```bash
mysql -uroot -p your_db < agent-kit-starter/src/main/resources/db/agent-kit-schema.sql
```

十张表：`ai_run` / `ai_llm_call` / `ai_tool_call` / `ai_eval_score` / `ai_feedback` /
`ai_golden_case` / `ai_price` / `ai_agent_session` / `ai_agent_message` / `ai_agent_memory`。
都是纯日志或脚手架自有数据，可按时间清理。

### 3.3 最小配置

```yaml
agent-kit:
  provider: deepseek
  base-url: https://api.deepseek.com
  model: deepseek-chat
  api-key: ${DEEPSEEK_API_KEY}
  fallback-provider: ollama      # 可选：云端挂了走本地
```

完整配置项见 `agent-kit-starter/src/main/resources/agent-kit-example.yml`。

### 3.4 写一个工具

```java
@Component
public class OrderQueryTool implements AgentTool {

    private final OrderMapper orderMapper;   // 你自己的 Mapper

    @Override public String name() { return "order_query"; }

    @Override public String description() {
        return "按订单号或手机号查询订单。用户问「我的订单到哪了」时调用。";
    }

    @Override public JSONObject parametersSchema() {
        return JSON.parseObject("""
            {"type":"object","properties":{
              "orderNo":{"type":"string","description":"订单号，可选"},
              "phone":{"type":"string","description":"下单手机号，可选"}
            },"required":[]}
            """);
    }

    @Override public ToolOutcome execute(Map<String, Object> args) {
        List<Order> rows = orderMapper.search(args);
        if (rows.isEmpty()) {
            return ToolOutcome.ok("没有查到符合条件的订单。", 0, rows);
        }
        return ToolOutcome.ok(toMarkdown(rows), rows.size(), rows);
    }
}
```

`ToolOutcome` 里的 `summary` 是**给模型看的人话**，`rows` 是行数（用于「返回 0 条却下结论」的检测），
`data` 是原始结果（给前端或后续步骤用）。

### 3.5 写一个子 Agent

```java
@Component
public class OrderAgent implements Agent {

    @Override public String name() { return "order"; }

    @Override public String description() {
        return "查询订单状态、物流、退换货进度；也受理改地址、取消订单。";
    }

    @Override public Set<String> toolNames() {
        return Set.of("order_query", "order_update_address", "order_cancel");
    }

    @Override public String systemPrompt() {
        return """
               你是订单助手。只回答与订单相关的问题；用户要办的事超出工具能力时如实说明。
               不要编造物流单号或时间，所有结论必须来自工具结果。
               """;
    }
}
```

注册即生效：`AgentRegistry` 自动收集所有 `Agent` Bean，`IntentRouter` 的候选清单由
`name() + description()` 生成——**新增领域不需要改路由代码**。

### 3.6 调用

```java
// 入口处绑定调用方标识（可空；不绑也能跑，只是观测里 subject_id 为空）
RunContext.set(RunContext.Subject.of("u-10086", Map.of("channel", "web")));
RunScope.bindSession(sessionId);          // 可选：把 run 与会话关联起来
try {
    AgentResult result = chatEngine.handle(history, userMessage, lastAgentName, memories, sink);
    // result.reply() 是最终回复，result.events() 是执行过的工具动作
} finally {
    RunScope.unbindSession();
    RunContext.clear();                    // 不清会串号（线程池复用）
}
```

### 3.7 跑通示例

不想从零拼装就先看 `examples/agent-kit-example`：一个假业务（待办 + 天气）把
Agent、Tool、RunContext、会话与记忆四层全串起来了，包含可直接 curl 的 HTTP 入口。

```bash
mvn -Pexamples clean install -DskipTests
mysql -uroot -p agent_kit_demo < agent-kit-starter/src/main/resources/db/agent-kit-schema.sql
export MYSQL_PASSWORD=...  REDIS_PASSWORD=...
java -jar examples/agent-kit-example/target/agent-kit-example-0.1.0.jar

curl -s localhost:18080/demo/catalog                 # 看装了几个 Agent / 工具
curl -s localhost:18080/demo/chat -H 'Content-Type: application/json' \
  -d '{"subjectId":"u-42","role":"owner","message":"帮我记一下周五交房租"}'
```

文件索引与「业务侧抄哪几个类」见 [`examples/README.md`](examples/README.md)。

---

## 4. 五个扩展点

| 扩展点 | 接口 | 用途 | 不实现时 |
|---|---|---|---|
| 模型渠道 | `ModelProvider` | 接新厂商；OpenAI 兼容的继承 `OpenAiCompatibleProvider` 只改 id | 内置 deepseek / siliconflow / ollama |
| 工具 | `AgentTool` | 业务能力 | 无工具，纯对话 |
| 子 Agent | `Agent` | 领域拆分（提示词 + 工具白名单） | 主 Agent 直接回话 |
| 工具横切 | `ToolInterceptor` | 权限、审计、限流、脱敏（可多个，用 `@Order` 定顺序） | 全放行 |
| 每轮钩子 | `TurnHook` | 预取数据、开作用域、埋自定义指标 | 无操作 |
| 渠道会话 | `SessionSourceRegistry` | 解绑后隐藏历史会话（IM 场景） | 按 `owner` 过滤 |

`ToolInterceptor` 是**唯一**的拦截点，也是权限该落地的地方：

```java
@Component
public class PermissionInterceptor implements ToolInterceptor {
    @Override public String before(String toolName, Map<String, Object> args) {
        return currentUserCan(toolName) ? null : "当前账号没有该操作权限";
    }
}
```

返回的字符串会原样回灌给模型，模型会如实告诉用户，而不是反复重试。

---

## 5. 隔离原语：namespace + owner

脚手架里所有「数据属于谁」的问题，答案都是两个不透明字符串：

| 字段 | 含义 | 例子 |
|---|---|---|
| `namespace` | 分区键，检索/查询的**硬边界** | `tenant:1`、`org:7`、`bot:sales` |
| `owner` | 归属方；`null` 表示分区内**共享** | `u-10086`、`ticket-20261117-01`、`device-abc` |

由此可以表达所有常见形态，而不需要脚手架知道其中任何一种：

| 场景 | namespace | owner |
|---|---|---|
| 单用户单机 | `default` | `me` |
| 多用户 SaaS | `tenant:{tenantId}` | `{userId}` |
| 团队共享知识 + 个人偏好 | `team:{teamId}` | `{userId}`（共享记忆传 `null`） |
| 多机器人实例 | `bot:{botId}` | `{chatId}` |

会话与记忆的读写都要显式传这两个参数（`AgentSessionStore` 不读线程上下文）——
这是刻意的：一旦它们依赖 ThreadLocal，任何一次「忘了先设上下文」都会静默读到别人的数据。

---

## 6. 可观测

三张明细表 + 一套指标 + 一条 trace，三者用同一个 `run_id` / `trace_id` 串起来。

```
ai_run          一次任务一行：tokens / 成本 / 耗时 / TTFT / 状态 / 质量标签
ai_llm_call     一次模型调用一行：provider / model / purpose / 缓存命中 / 价格
ai_tool_call    一次工具执行一行：参数摘要 / 状态 / 返回行数 / 耗时
```

- **指标**：Micrometer → Prometheus，`ai_*` 命名；标签只用低基数枚举（agent / provider / model / status / tool），
  绝不放 `subject_id`、`session_id`、`trace_id`（会按调用方数量线性膨胀）。
- **Trace**：OTel → Langfuse（`AiTracing`），可关（`agent-kit.obs.trace-io-enabled=false` 时不上报原文）。
- **写入**：`ObsWriter` 独立线程批量写，队列满则丢弃并计数——**埋点永不阻塞、永不抛异常**。

---

## 7. 质量评测

三层，可分别开关：

1. **确定性规则**（不花钱，默认开）：解析失败、空结果下结论、工具失败却说成功、超步数。
2. **LLM 评审**（`judge-enabled` + `judge-model`）：先判形态（answered / clarified / refused / failed），
   再按形态打分——澄清是正确行为，不该被当成「没回答」扣分。
3. **黄金集回归**（`golden-enabled`，`@Profile("golden")`）：固定用例 × N 轮，看 pass^k 与相对基线的回退。

---

## 8. 会话与长期记忆（`agent-kit-memory`）

- **会话**：`AgentSessionStore` 管会话与消息；`AgentContextCache` 用 Redis 打包「历史窗口 + 上轮子 Agent」，
  写穿 + `lastMessageId` 乐观校验，包落后一律回源。
- **锁**：`AgentSessionLock` 保证同一会话同时只有一轮在跑（防重复执行工具）。
- **记忆**：`AgentMemoryService` 走「提炼 → 冲突沉淀 → 检索注入」：
  - 每轮结束后异步让模型判断 ADD / UPDATE / DROP，只记跨会话稳定信息；
  - UPDATE/DROP 不物理删，旧记录置 `SUPERSEDED` 保留痕迹；
  - 检索按 `namespace + owner`（含共享）向量召回，规则单独留槽位排在前面；
  - Milvus 只是可重建索引，丢了就按 `ai_agent_memory` 全量灌回。

Milvus collection 结构（首次启动自动创建，结构与代码不一致时自动 drop 重建）：

```
id         INT64        主键
namespace  VARCHAR(128) 分区
owner      VARCHAR(128) 归属（共享写空串）
vector     FLOAT_VECTOR 维度 = agent-kit.memory.dimensions
索引        AUTOINDEX / COSINE
```

---

## 9. 引擎选择

```yaml
agent-kit:
  engine: legacy   # legacy（默认，自研薄运行时）/ agentscope（外部 ReActAgent）
```

两套引擎共用 `IntentRouter`、`Agent`、`AgentTool`、观测与评测，因此可以 A/B 对比而不用改业务代码。

---

## 10. 构建

```bash
mvn -o clean package -DskipTests
```

要求 JDK 17、Maven 3.9+。运行期依赖：MySQL 8（观测与记忆正文）、Redis（会话缓存与锁，可选）、
Milvus（向量检索，可选）、一个 OpenAI 兼容的模型服务（必需）。

---

## License

内部脚手架，暂未开源授权。使用前请联系维护者。
