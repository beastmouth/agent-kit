package com.beastmouth.agentkit.obs;

/**
 * 字符 → token 的兜底估算。
 *
 * <p>只在网关不返回 usage 时使用，结果必须标记 {@code usage_estimated=1}。
 * 经验值：中文约 1.7 字/token，英文约 4 字符/token；混排时按字符类别分别折算。</p>
 *
 * <p>这不是精确值，它的用途只有一个：<b>不让成本报表出现整片 0</b>。
 * 真要比对账，以厂商后台账单为准（自检清单里那条 5% 误差）。</p>
 */
final class Tokens {

    private Tokens() {
    }

    static int estimate(int chars) {
        if (chars <= 0) {
            return 0;
        }
        // 这里拿不到原文字符，只能按中位比例整体折算，取中文为主的保守值
        return Math.max(1, (int) Math.ceil(chars / 1.7d));
    }

    static int estimate(String text) {
        return text == null ? 0 : estimate(text.length());
    }
}
