package com.beastmouth.agentkit.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 嵌入客户端：OpenAI 兼容的 {@code POST /embeddings}。
 *
 * <p>默认指向本地 Ollama 的 {@code /v1}（bge-m3）；SiliconFlow 等云端嵌入
 * 协议相同，换 base-url 与 api-key 即可。专用于长期记忆的向量化——
 * 与对话模型通道（ModelProvider）分开，嵌入挂了不能拖垮对话。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    private final MemoryProperties properties;

    private volatile HttpClient httpClient;

    /**
     * 把一段文本向量化。失败抛 RuntimeException，由调用方决定降级方式。
     */
    public float[] embed(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("嵌入内容不能为空");
        }
        HttpClient client = httpClient();
        JSONObject body = new JSONObject();
        body.put("model", properties.getModel());
        body.put("input", text);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(properties.getBaseUrl()) + "/embeddings"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader())
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("嵌入服务返回 " + response.statusCode() + ": "
                        + abbreviate(response.body()));
            }
            JSONArray embedding = JSON.parseObject(response.body())
                    .getJSONArray("data").getJSONObject(0).getJSONArray("embedding");
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = embedding.getFloatValue(i);
            }
            if (vector.length != properties.getDimensions()) {
                throw new IllegalStateException("嵌入维度不符：期望 " + properties.getDimensions()
                        + " 实际 " + vector.length + "（检查 memory.dimensions 与模型是否一致）");
            }
            return vector;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("嵌入请求被中断", e);
        } catch (java.io.IOException | RuntimeException e) {
            throw new IllegalStateException("嵌入请求失败：" + e.getMessage(), e);
        }
    }

    public List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    private HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .build();
                }
                client = httpClient;
            }
        }
        return client;
    }

    private String authHeader() {
        return StringUtils.hasText(properties.getApiKey())
                ? "Bearer " + properties.getApiKey()
                : "Bearer none";
    }

    private String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }
}
