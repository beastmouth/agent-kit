package com.beastmouth.agentkit.provider;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.beastmouth.agentkit.config.AiProperties;
import com.beastmouth.agentkit.model.ChatMessage;
import com.beastmouth.agentkit.model.ModelReply;
import com.beastmouth.agentkit.support.AgentKitException;
import com.beastmouth.agentkit.support.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 本地 Ollama 提供方，走 {@code /api/chat}。
 *
 * <p>不依赖任何 SDK：请求体就是 JSON，用 fastjson2 拼装与解析，避免为换模型引一套框架。
 * {@code jsonMode=true} 时带 {@code format=json}，让模型只吐 JSON，结构化工具协议依赖这一点。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaModelProvider implements ModelProvider {

    /** 与 {@code agent-kit.provider} 对应 */
    public static final String ID = "ollama";

    private final AiProperties properties;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean streaming() {
        return properties.streamOf(ID);
    }

    @Override
    public ModelReply chat(List<ChatMessage> messages, boolean jsonMode) {
        long start = System.currentTimeMillis();
        AiProperties.Endpoint endpoint = properties.endpoint(ID);
        HttpRequest request = buildRequest(endpoint, payload(endpoint, messages, jsonMode, false));
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ensureOk(response.statusCode(), response.body());
            JSONObject body = JSON.parseObject(response.body());
            JSONObject message = body.getJSONObject("message");
            String content = message == null ? "" : message.getString("content");
            long elapsed = System.currentTimeMillis() - start;
            log.info("OllamaModelProvider#chat#完成 model={} json={} 耗时={}ms out={} tokens",
                    endpoint.getModel(), jsonMode, elapsed, body.getIntValue("eval_count"));
            return new ModelReply(content, body.getIntValue("prompt_eval_count"),
                    body.getIntValue("eval_count"), elapsed);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("OllamaModelProvider#chat#调用失败 model={} err={}", endpoint.getModel(), e.toString());
            throw AgentKitException.of(ErrorCode.MODEL_UNAVAILABLE);
        }
    }

    @Override
    public ModelReply stream(List<ChatMessage> messages, Consumer<String> onDelta) {
        long start = System.currentTimeMillis();
        AiProperties.Endpoint endpoint = properties.endpoint(ID);
        HttpRequest request = buildRequest(endpoint, payload(endpoint, messages, false, true));
        StringBuilder buffer = new StringBuilder();
        int outputTokens = 0;
        try {
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            try (Stream<String> lines = response.body()) {
                if (response.statusCode() != 200) {
                    ensureOk(response.statusCode(), lines.findFirst().orElse(""));
                }
                for (String line : (Iterable<String>) lines::iterator) {
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    JSONObject chunk = JSON.parseObject(line);
                    JSONObject message = chunk.getJSONObject("message");
                    String piece = message == null ? "" : message.getString("content");
                    if (piece != null && !piece.isEmpty()) {
                        buffer.append(piece);
                        if (onDelta != null) {
                            onDelta.accept(piece);
                        }
                    }
                    if (chunk.getBooleanValue("done")) {
                        outputTokens = chunk.getIntValue("eval_count");
                    }
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("OllamaModelProvider#stream#完成 model={} 耗时={}ms 字符={}",
                    endpoint.getModel(), elapsed, buffer.length());
            return new ModelReply(buffer.toString(), 0, outputTokens, elapsed);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("OllamaModelProvider#stream#调用失败 model={} err={}", endpoint.getModel(), e.toString());
            throw AgentKitException.of(ErrorCode.MODEL_UNAVAILABLE);
        }
    }

    /** 拼请求体；流式与非流式只差 stream 字段 */
    private String payload(AiProperties.Endpoint endpoint, List<ChatMessage> messages,
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
        if (jsonMode) {
            body.put("format", "json");
        }
        // 关闭思考模式：支持的模型会直接给答案，不支持的（如 deepseek-r1）会忽略该字段
        if (endpoint.getThink() != null) {
            body.put("think", endpoint.getThink());
        }
        JSONObject options = new JSONObject();
        options.put("temperature", properties.getTemperature());
        options.put("num_predict", properties.getMaxOutputTokens());
        body.put("options", options);
        return body.toJSONString();
    }

    private HttpRequest buildRequest(AiProperties.Endpoint endpoint, String payload) {
        return HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(endpoint.getBaseUrl()) + "/api/chat"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (endpoint.getApiKey() == null ? "" : endpoint.getApiKey()))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
    }

    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void ensureOk(int status, String body) {
        if (status != 200) {
            log.warn("OllamaModelProvider#ensureOk#状态异常 status={} body={}", status,
                    body == null ? "" : body.substring(0, Math.min(200, body.length())));
            throw AgentKitException.of(ErrorCode.MODEL_UNAVAILABLE);
        }
    }
}
