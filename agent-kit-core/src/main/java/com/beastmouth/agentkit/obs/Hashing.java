package com.beastmouth.agentkit.obs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 只做一件小事：给用户输入算 sha256，用于重复提问检测与去重统计 */
final class Hashing {

    private Hashing() {
    }

    static String sha256(String text) {
        if (text == null || text.isEmpty()) {
            return "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // 理论不可能发生；观测不应因此影响业务
            return "unavailable";
        }
    }
}
