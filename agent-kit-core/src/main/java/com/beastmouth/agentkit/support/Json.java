package com.beastmouth.agentkit.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * JSON 读写，仅封装脚手架内部用得上的两个方向。
 *
 * <p>刻意不暴露成「工具类大杂烩」：观测层只需要「把对象序列化进 trace 属性」，
 * 不需要日期格式、序列化器注册这些花样。要定制请直接在业务侧用 fastjson2。</p>
 */
public final class Json {

    private Json() {
    }

    /** 序列化；值为 null 时返回 null（trace 属性里 null 与 "{}" 含义不同） */
    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.toJSONString(value);
        } catch (RuntimeException e) {
            // 序列化失败不能影响主链路：观测是旁路，拿不到字符串就记 null
            return null;
        }
    }

    /** 解析成对象，失败返回 null 而不抛（模型输出不可信，调用方需要自己判空） */
    public static JSONObject parseObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 解析成指定类型（缓存包这类内部结构用），失败返回 null 而不抛 */
    public static <T> T parseObject(String text, Class<T> type) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(text, type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 解析并去掉可能的 markdown 代码块包裹（模型很爱在 JSON 外面套 ```json） */
    public static JSONObject parseLoose(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            int firstLine = cleaned.indexOf('\n');
            if (firstLine > 0) {
                cleaned = cleaned.substring(firstLine + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();
        }
        return parseObject(cleaned);
    }
}
