package com.beastmouth.agentkit.obs;

import com.beastmouth.agentkit.obs.mapper.AiPriceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 成本计算：价目进表，代码里不写价格。
 *
 * <p>口径（按百万 token 计价，结果保留 6 位小数）：</p>
 * <pre>
 * cost = (缓存命中输入 × 命中价 + 未命中输入 × 未命中价 + 输出 × 输出价) / 1_000_000
 *        × 峰时倍数（落在峰时窗口内才乘）
 * </pre>
 *
 * <p>找不到价目时返回 0 并计数（{@code ai.obs.price_missing}），<b>绝不抛异常</b>——
 * 一条算不出价的调用不该让对话失败，但必须让报表上出现"未知价格"的信号。</p>
 */
@Slf4j
@Component
public class CostCalculator {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final AiPriceMapper mapper;
    private final ObsProperties properties;

    /** 查不到价目的次数：P1 引入 Micrometer 后改抛指标，现在先留可读的计数 */
    private final AtomicLong priceMissing = new AtomicLong();

    private final Map<String, ModelPrice> cache = new ConcurrentHashMap<>();
    private volatile long loadedAt;

    public CostCalculator(AiPriceMapper mapper, ObsProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 价目缺失次数，健康检查/报表页可以读它 */
    public long priceMissingCount() {
        return priceMissing.get();
    }

    public BigDecimal calc(String provider, String model,
                           int inputTokens, int cachedTokens, int outputTokens, LocalDateTime at) {
        try {
            ModelPrice price = priceOf(provider, model);
            if (price == null) {
                long n = priceMissing.incrementAndGet();
                if (n == 1 || n % 500 == 0) {
                    log.warn("CostCalculator#calc#价目缺失 provider={} model={} 累计={}，先按 0 计", provider, model, n);
                }
                return BigDecimal.ZERO;
            }
            int hit = Math.min(Math.max(cachedTokens, 0), Math.max(inputTokens, 0));
            int miss = Math.max(inputTokens - hit, 0);

            BigDecimal cost = BigDecimal.valueOf(hit).multiply(price.getCacheHitPerM())
                    .add(BigDecimal.valueOf(miss).multiply(price.getCacheMissPerM()))
                    .add(BigDecimal.valueOf(Math.max(outputTokens, 0)).multiply(price.getOutputPerM()))
                    .divide(MILLION, 6, RoundingMode.HALF_UP);

            if (inPeak(price, at)) {
                cost = cost.multiply(price.getPeakMultiplier());
            }
            return cost.setScale(6, RoundingMode.HALF_UP);
        } catch (RuntimeException e) {
            priceMissing.incrementAndGet();
            return BigDecimal.ZERO;
        }
    }

    private ModelPrice priceOf(String provider, String model) {
        long now = System.currentTimeMillis();
        if (now - loadedAt > properties.getPriceRefreshSeconds() * 1000L || cache.isEmpty()) {
            refresh();
        }
        return cache.get(key(provider, model));
    }

    private synchronized void refresh() {
        long now = System.currentTimeMillis();
        if (now - loadedAt < 1000) {
            return;   // 并发下的抖动保护，避免同一秒反复打库
        }
        try {
            List<ModelPrice> list = mapper.selectEffective(LocalDate.now());
            Map<String, ModelPrice> next = new ConcurrentHashMap<>();
            for (ModelPrice p : list) {
                // 同一模型可能有多条历史价目，按生效日期取最新
                next.put(key(p.getProvider(), p.getModel()), p);
            }
            cache.putAll(next);
            loadedAt = now;
        } catch (RuntimeException e) {
            loadedAt = now;   // 失败也退避，不要每条调用都去撞库
        }
    }

    private static String key(String provider, String model) {
        return provider + "/" + model;
    }

    /** 峰时窗口形如 "09:00-12:00,14:00-18:00" */
    private static boolean inPeak(ModelPrice price, LocalDateTime at) {
        if (price.getPeakWindows() == null || price.getPeakWindows().isBlank()) {
            return false;
        }
        LocalDateTime time = at == null ? LocalDateTime.now() : at;
        LocalTime t = time.toLocalTime();
        for (String window : price.getPeakWindows().split(",")) {
            String[] parts = window.trim().split("-");
            if (parts.length != 2) {
                continue;
            }
            try {
                if (!t.isBefore(LocalTime.parse(parts[0])) && t.isBefore(LocalTime.parse(parts[1]))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 配置写错就当作非峰时，不影响主流程
            }
        }
        return false;
    }

    /** 供报表页直接查（不走缓存），用于"这个月花了多少"这类聚合 */
    public List<ModelPrice> allPrices() {
        try {
            return new ArrayList<>(mapper.selectEffective(LocalDate.now()));
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
