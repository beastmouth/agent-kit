package com.beastmouth.agentkit.provider;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.model.ModelReply;
import com.beastmouth.agentkit.support.AgentKitException;
import com.beastmouth.agentkit.support.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OpenAI 兼容协议的通用实现，子类只提供一个 {@link #id()}。
 *
 * <p>协议是 {@code POST {base-url}/chat/completions} + {@code Authorization: Bearer <key>}，
 * 硅基流动、DeepSeek 官方、通义、vLLM、one-api 全都一样，差异只在
 * {@code base-url} / {@code model} / {@code api-key}，所以这些全部走
 * {@code agent-kit.endpoints.<id>} 配置，代码里不出现厂商分支。</p>
 *
 * <p>几个必须注意的实现细节：</p>
 * <ul>
 *   <li><b>强制 HTTP/1.1</b>：Java HttpClient 默认会尝试 h2c/HTTP2 升级，部分网关（以及反向代理）
 *       处理不当会把请求体吞掉，表现为「参数明明传了，服务端却收到空 body」。</li>
 *   <li><b>response_format 可降级</b>：{@code json_object} 并非所有模型都支持，被拒时退化成
 *       「只靠提示词约束 + 容错解析」，而不是整个请求失败。</li>
 *   <li><b>思考开关按厂商透传</b>：DeepSeek 用 {@code thinking:{type:enabled|disabled}}，
 *       其他家未必认这个字段，所以只在 {@code endpoints.<id>.thinking} 显式配置时才发送。</li>
 * </ul>
 */
@Slf4j
public abstract class OpenAiCompatibleProvider implements ModelProvider {

    private final AiProperties properties;
    private final String id;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    protected OpenAiCompatibleProvider(AiProperties properties, String id) {
        this.properties = properties;
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    /**
     * 空响应重试时的补位提醒。
     *
     * <p>实测（2026-10-30）：DeepSeek 开启 {@code response_format=json_object} 后，遇到特定
     * 多轮历史会<b>确定性</b>地产出「纯空白正文」（content 全是空格、finish=stop、completion_tokens
     * 正常），调温度、改 system 提示词都救不回来；唯一有效的扰动是在消息末尾追加一条 user
     * 提醒——它改变了采样上下文，且模型仍按协议输出 JSON（8/8 空响应的会话重放后 4/4 恢复）。
     * 提醒里带「JSON」字样也顺带满足 DeepSeek 对 json_object 模式的提示词要求。</p>
     */
    private static final String RETRY_NUDGE = "（系统提醒：请按上面的输出协议，直接输出 JSON 对象本身，"
            + "以 { 开始、以 } 结束，不要输出空白或其他文字。）";

    @Override
    public ModelReply chat(List<ChatMessage> messages, boolean jsonMode) {
        ModelReply reply = doChat(messages, jsonMode);
        if (jsonMode && (reply.content() == null || reply.content().isBlank())) {
            // 空白正文不是「模型没话说」，而是 JSON 模式的退化输出：直接返回会让路由失明
            //（用户追问「前天呢」得到的将是无关兜底话术）、让子 Agent 白跑一轮工具。
            log.warn("{}#chat#JSON 模式返回空白正文，追加提醒后重试 model={}", id,
                    properties.endpoint(id).getModel());
            List<ChatMessage> retried = new ArrayList<>(messages);
            retried.add(ChatMessage.user(RETRY_NUDGE));
            reply = doChat(retried, jsonMode);
        }
        return reply;
    }

    private ModelReply doChat(List<ChatMessage> messages, boolean jsonMode) {
        long start = System.currentTimeMillis();
        AiProperties.Endpoint endpoint = properties.endpoint(id);
        try {
            JSONObject body = payload(endpoint, messages, jsonMode, false);
            HttpResponse<String> response = send(endpoint, body);
            if (response.statusCode() == 400 && jsonMode && mentionsResponseFormat(response.body())) {
                // 模型不支持 json_object：去掉该字段重试一次，别让整轮对话失败
                log.warn("{}#chat#模型不支持 response_format，去掉后重试 model={}", id, endpoint.getModel());
                body.remove("response_format");
                response = send(endpoint, body);
            }
            ensureOk(response.statusCode(), response.body());

            JSONObject payload = JSON.parseObject(response.body());
            JSONObject message = firstMessage(payload);
            String content = contentOf(message);
            long elapsed = System.currentTimeMillis() - start;
            int prompt = promptTokens(payload);
            int cached = cachedTokens(payload);
            log.info("{}#chat#完成 model={} json={} 耗时={}ms 字符={} in={} cached={}({}%)",
                    id, endpoint.getModel(), jsonMode, elapsed, content.length(),
                    prompt, cached, percent(cached, prompt));
            return new ModelReply(content, prompt, completionTokens(payload), cached, elapsed);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("{}#chat#调用失败 model={} err={}", id, endpoint.getModel(), e.toString());
            throw AgentKitException.of(ErrorCode.MODEL_UNAVAILABLE);
        }
    }

    @Override
    public ModelReply stream(List<ChatMessage> messages, Consumer<String> onDelta) {
        long start = System.currentTimeMillis();
        AiProperties.Endpoint endpoint = properties.endpoint(id);
        JSONObject body = payload(endpoint, messages, false, true);
        StringBuilder buffer = new StringBuilder();
        // 0=输入 1=输出 2=命中；未取到时保持 0，由观测层标记为「估算」
        int[] usage = new int[3];
        try {
            HttpResponse<Stream<String>> response = httpClient.send(buildRequest(endpoint, body),
                    HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                String errorBody = drain(response.body());
                if (response.statusCode() == 400 && errorBody.contains("stream_options")) {
                    // 网关不认 stream_options：去掉重试一次。拿不到 usage 只是报表变估算，
                    // 让整轮对话失败才是真事故
                    log.warn("{}#stream#网关不认 stream_options，去掉后重试 model={}", id, endpoint.getModel());
                    body.remove("stream_options");
                    response = httpClient.send(buildRequest(endpoint, body), HttpResponse.BodyHandlers.ofLines());
                    if (response.statusCode() != 200) {
                        errorBody = drain(response.body());
                        ensureOk(response.statusCode(), errorBody);
                    }
                } else {
                    ensureOk(response.statusCode(), errorBody);
                }
            }
            try (Stream<String> lines = response.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (line == null || line.isBlank() || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JSONObject chunk = parseChunk(data);
                    if (chunk == null) {
                        continue;
                    }
                    JSONObject choice = firstChoice(chunk);
                    JSONObject delta = choice == null ? null : choice.getJSONObject("delta");
                    String piece = delta == null ? null : delta.getString("content");
                    if (piece != null && !piece.isEmpty()) {
                        buffer.append(piece);
                        if (onDelta != null) {
                            onDelta.accept(piece);
                        }
                    }
                    // 开了 stream_options.include_usage 后，末尾会有一片 choices 为空、只带 usage 的包
                    JSONObject chunkUsage = chunk.getJSONObject("usage");
                    if (chunkUsage != null) {
                        usage[0] = chunkUsage.getIntValue("prompt_tokens");
                        usage[1] = chunkUsage.getIntValue("completion_tokens");
                        usage[2] = cachedOf(chunkUsage);
                    }
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("{}#stream#完成 model={} 耗时={}ms 字符={} in={} cached={}({}%)",
                    id, endpoint.getModel(), elapsed, buffer.length(),
                    usage[0], usage[2], percent(usage[2], usage[0]));
            return new ModelReply(buffer.toString(), usage[0], usage[1], usage[2], elapsed);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("{}#stream#调用失败 model={} err={}", id, endpoint.getModel(), e.toString());
            throw AgentKitException.of(ErrorCode.MODEL_UNAVAILABLE);
        }
    }

    /**
     * 把错误响应读成字符串。
     *
     * <p>流式用的是 {@code ofLines}，状态码不对时流还没消费；不读掉就直接抛异常，
     * 连接会一直挂到超时。错误体不大，限 50 行足够把 {@code stream_options} 之类的关键字捞出来。</p>
     */
    private static String drain(Stream<String> body) {
        if (body == null) {
            return "";
        }
        try (Stream<String> lines = body) {
            return lines.limit(50).collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    /** 拼请求体；流式与非流式只差 stream 字段 */
    private JSONObject payload(AiProperties.Endpoint endpoint, List<ChatMessage> messages,
                               boolean jsonMode, boolean stream) {
        JSONObject body = new JSONObject();
        body.put("model", endpoint.getModel());
        JSONArray array = new JSONArray();
        for (ChatMessage message : messages) {
            JSONObject item = new JSONObject();
            item.put("role", message.role());
            item.put("content", message.content());
            array.add(item);
        }
        body.put("messages", array);
        body.put("stream", stream);
        // 流式不主动要 usage 就拿不到 token 与命中数，观测层只能按字符瞎估
        if (stream && properties.isStreamUsage()) {
            JSONObject options = new JSONObject();
            options.put("include_usage", true);
            body.put("stream_options", options);
        }
        body.put("temperature", properties.getTemperature());
        body.put("max_tokens", properties.getMaxOutputTokens());
        if (jsonMode) {
            JSONObject format = new JSONObject();
            format.put("type", "json_object");
            body.put("response_format", format);
        }
        // DeepSeek 用 thinking.type 控开关；没显式配就不发，免得别的网关因未知字段 400
        if (endpoint.getThinking() != null) {
            JSONObject thinking = new JSONObject();
            thinking.put("type", Boolean.TRUE.equals(endpoint.getThinking()) ? "enabled" : "disabled");
            body.put("thinking", thinking);
        }
        if (StringUtils.hasText(endpoint.getReasoningEffort())) {
            body.put("reasoning_effort", endpoint.getReasoningEffort());
        }
        return body;
    }

    private HttpResponse<String> send(AiProperties.Endpoint endpoint, JSONObject body)
            throws IOException, InterruptedException {
        return httpClient.send(buildRequest(endpoint, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest buildRequest(AiProperties.Endpoint endpoint, JSONObject body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(endpoint.getBaseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (endpoint.getApiKey() == null ? "" : endpoint.getApiKey()))
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                .build();
    }

    private JSONObject firstMessage(JSONObject payload) {
        JSONObject choice = firstChoice(payload);
        return choice == null ? null : choice.getJSONObject("message");
    }

    private JSONObject firstChoice(JSONObject payload) {
        JSONArray choices = payload.getJSONArray("choices");
        return choices == null || choices.isEmpty() ? null : choices.getJSONObject(0);
    }

    /** 内容取 content；部分推理模型把正文放在 reasoning_content 里时要能兜住 */
    private String contentOf(JSONObject message) {
        if (message == null) {
            return "";
        }
        String content = message.getString("content");
        if (content != null && !content.isBlank()) {
            return content;
        }
        String reasoning = message.getString("reasoning_content");
        return reasoning == null ? "" : reasoning;
    }

    private JSONObject parseChunk(String data) {
        try {
            return JSON.parseObject(data);
        } catch (RuntimeException e) {
            log.debug("{}#parseChunk#忽略非 JSON 片段 {}", id, data);
            return null;
        }
    }

    private int promptTokens(JSONObject payload) {
        JSONObject usage = payload.getJSONObject("usage");
        return usage == null ? 0 : usage.getIntValue("prompt_tokens");
    }

    private int completionTokens(JSONObject payload) {
        JSONObject usage = payload.getJSONObject("usage");
        return usage == null ? 0 : usage.getIntValue("completion_tokens");
    }

    private int cachedTokens(JSONObject payload) {
        return cachedOf(payload == null ? null : payload.getJSONObject("usage"));
    }

    /**
     * 缓存命中的两个口径，取到哪个算哪个：
     * <ul>
     *   <li>DeepSeek：{@code prompt_cache_hit_tokens}；</li>
     *   <li>OpenAI 兼容：{@code prompt_tokens_details.cached_tokens}。</li>
     * </ul>
     * 都没有就是 0（网关不支持缓存或没命中），<b>不能当成「一定没命中」写死</b>——
     * 这正是之前成本报表把输入价高估 50 倍的根因。
     */
    private static int cachedOf(JSONObject usage) {
        if (usage == null) {
            return 0;
        }
        int hit = usage.getIntValue("prompt_cache_hit_tokens");
        if (hit <= 0) {
            JSONObject details = usage.getJSONObject("prompt_tokens_details");
            if (details != null) {
                hit = details.getIntValue("cached_tokens");
            }
        }
        return Math.max(hit, 0);
    }

    private static int percent(int part, int total) {
        return total <= 0 ? 0 : (int) Math.round(part * 100.0 / total);
    }

    private boolean mentionsResponseFormat(String body) {
        return body != null && body.contains("response_format");
    }

    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void ensureOk(int status, String body) {
        if (status != 200) {
            log.warn("{}#ensureOk#状态异常 status={} body={}", id, status,
                    body == null ? "" : body.substring(0, Math.min(300, body.length())));
            throw AgentKitException.of(ErrorCode.MODEL_UNAVAILABLE);
        }
    }
}
