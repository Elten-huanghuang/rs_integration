package com.huanghuang.rsintegration.api.resonance;

import com.huanghuang.rsintegration.api.ResourceMatchMode;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 一次共振存储访问的短生命周期快照。
 *
 * <p>快照创建后保留逻辑条目身份，但每次修改前仍会校验 live revision。
 * 提取必须先使用 {@code simulate=true} 检查，再根据调用方语义执行实际提取。</p>
 */
public interface ResonanceInventory {
    long revision();

    List<String> backendIds();

    long stored();

    long capacity();

    /**
     * 返回所有逻辑条目的独立物品副本。
     */
    List<ResonanceEntry> entries();

    List<ResonanceEntry> find(
            ItemStack template,
            ResourceMatchMode matchMode
    );

    boolean hasItem(
            ItemStack template,
            ResourceMatchMode matchMode
    );

    long count(
            ItemStack template,
            ResourceMatchMode matchMode
    );

    /**
     * 从指定逻辑条目精确提取。
     *
     * @param entryId 当前 handle 返回的临时条目 ID
     * @param amount  请求数量，必须为正数
     * @param simulate true 时只验证，不修改真实存储
     */
    ResonanceResult<ResonanceExtraction> extract(
            long entryId,
            int amount,
            boolean simulate
    );

    /**
     * 插入一个未分配逻辑槽位的物品。
     */
    ResonanceResult<ResonanceInsertion> insert(
            ItemStack stack,
            boolean simulate
    );
}
