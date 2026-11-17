package com.beastmouth.agentkit.obs.mapper;

import com.beastmouth.agentkit.obs.LlmCallRow;
import com.beastmouth.agentkit.obs.ModelPrice;
import com.beastmouth.agentkit.obs.RunRow;
import com.beastmouth.agentkit.obs.ToolCallRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 价目读取。只读，改价走运维 SQL / 后台页面，代码里不出现价格常量。
 */
@Mapper
public interface AiPriceMapper {

    /**
     * 取每个 model 在指定日期前最新的一条价目。
     *
     * <p>原来是 {@code JOIN (SELECT MAX(effective_from) ... GROUP BY)} 的自连接子查询，
     * 现在拆成「一次单表查询 + 内存取每组第一条」：价目表只有几十行，
     * 全量读回来按 {@code provider/model} 去重完全是内存操作，却省掉了
     * 一层子查询——价目表将来若按 provider 分片，自连接同样要跨片。</p>
     */
    default List<ModelPrice> selectEffective(LocalDate today) {
        return latestPerModel(selectAllEffectiveBefore(today));
    }

    /**
     * 每个模型取生效日期最新的那条价目。
     *
     * <p>抽成静态方法便于单测：去重键必须带上 provider（不同厂商有同名模型），
     * 且依赖输入的倒序保证「第一条即最新」——这条约定写在 SQL 的 ORDER BY 里，
     * 单元测试就是防止有人改 SQL 时把顺序改掉。</p>
     */
    static List<ModelPrice> latestPerModel(List<ModelPrice> rows) {
        Map<String, ModelPrice> latest = new LinkedHashMap<>();
        if (rows != null) {
            for (ModelPrice price : rows) {
                latest.putIfAbsent(price.getProvider() + "/" + price.getModel(), price);
            }
        }
        return new ArrayList<>(latest.values());
    }

    /** 指定日期前的全部价目（单表，按模型 + 生效日期倒序） */
    @Select("""
            SELECT p.id,
                   p.provider,
                   p.model,
                   p.currency,
                   p.cache_hit_per_m  AS cacheHitPerM,
                   p.cache_miss_per_m AS cacheMissPerM,
                   p.output_per_m     AS outputPerM,
                   p.peak_multiplier  AS peakMultiplier,
                   p.peak_windows     AS peakWindows,
                   p.effective_from   AS effectiveFrom
            FROM ai_price p
            WHERE p.effective_from <= #{today}
            ORDER BY p.provider, p.model, p.effective_from DESC, p.id DESC
            """)
    List<ModelPrice> selectAllEffectiveBefore(@Param("today") LocalDate today);
}
