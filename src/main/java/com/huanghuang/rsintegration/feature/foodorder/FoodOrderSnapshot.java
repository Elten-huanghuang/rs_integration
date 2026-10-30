package com.huanghuang.rsintegration.feature.foodorder;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 一次列表构建的不可变结果。
 *
 * @param epoch         构建时的游戏时间，用于丢弃过期的下单请求
 * @param entries       已排序、已截断的条目
 * @param sources       存储来源说明（网络/背包）
 * @param totalUneaten  未吃食物总数（截断前）
 * @param totalOrderable 可下单条目数
 * @param indexing      配方索引是否仍在构建
 * @param partial       是否因为预算/上限只返回了部分结果
 */
public record FoodOrderSnapshot(
        long epoch,
        List<FoodOrderEntry> entries,
        Component sources,
        int totalUneaten,
        int totalOrderable,
        boolean indexing,
        boolean partial
) {

    public FoodOrderSnapshot {
        entries = entries == null ? List.of() : List.copyOf(entries);
        sources = sources == null ? Component.empty() : sources;
        totalUneaten = Math.max(0, totalUneaten);
        totalOrderable = Math.max(0, totalOrderable);
    }
}
