package com.beastmouth.agentkit.obs;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 价目表一行（{@code ai_price}）。
 *
 * <p>单位统一为 <b>元 / 百万 token</b>，与厂商定价页口径一致，避免换算时多除一次 1000。</p>
 */
@Data
public class ModelPrice {

    private Long id;
    private String provider;
    private String model;
    private String currency = "CNY";
    /** 缓存命中输入价 */
    private BigDecimal cacheHitPerM = BigDecimal.ZERO;
    /** 缓存未命中输入价 */
    private BigDecimal cacheMissPerM = BigDecimal.ZERO;
    /** 输出价 */
    private BigDecimal outputPerM = BigDecimal.ZERO;
    /** 峰时价格倍数，DeepSeek 为 2 */
    private BigDecimal peakMultiplier = BigDecimal.ONE;
    /** 峰时窗口，如 "09:00-12:00,14:00-18:00"，无峰谷留空 */
    private String peakWindows;
    private LocalDate effectiveFrom;
}
