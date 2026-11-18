package com.huanghuang.rsintegration.feature.sidepanel;

import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.Collections;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/** 侧栏条目的只读投影。预测归零时仍保留可渲染的物品。 */
public final class PanelStack {

    private final UUID id;
    private final ItemStack stack;
    public final long timestamp;
    public final boolean craftable;
    public final boolean zeroed;

    private String cachedName;
    private String cachedModId;
    private String cachedModName;
    private String cachedSearchKey;

    public PanelStack(UUID id, ItemStack stack, long timestamp, boolean craftable) {
        this(id, stack.copy(), timestamp, craftable, false);
    }

    private PanelStack(UUID id, ItemStack stack, long timestamp, boolean craftable, boolean zeroed) {
        this.id = id;
        this.stack = stack;
        this.zeroed = zeroed;
        this.timestamp = timestamp;
        this.craftable = craftable;
    }

    public UUID getId()          { return id; }

    /** Matches RS {@code IGridStack.getQuantity()}: returns 0 when zeroed. */
    public int getCount()        { return zeroed ? 0 : stack.getCount(); }

    PanelStack withQuantity(int count, long timestamp, boolean craftable) {
        return new PanelStack(id, count > 0 ? stack.copyWithCount(count) : stack,
                timestamp, craftable, count <= 0);
    }

    /** Always returns a renderable stack (count ≥ 1). */
    public ItemStack getStack()  { return stack.copy(); }

    public String getName() {
        if (cachedName == null) cachedName = stack.getHoverName().getString();
        return cachedName;
    }

    public String getModId() {
        if (cachedModId == null) {
            // getCreatorModId may return null for some modded items; default to
            // "minecraft" so downstream sort/search comparators never NPE.
            String id = stack.getItem().getCreatorModId(stack);
            cachedModId = id != null ? id : "minecraft";
        }
        return cachedModId;
    }

    public String getModName() {
        if (cachedModName == null) {
            String modId = getModId();
            cachedModName = ModList.get()
                    .getModContainerById(modId)
                    .map(c -> c.getModInfo().getDisplayName())
                    .orElse(modId);
        }
        return cachedModName;
    }

    public String searchKey() {
        if (cachedSearchKey == null) {
            cachedSearchKey = searchKeyOf(stack);
        }
        return cachedSearchKey;
    }

    public static String searchKeyOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        var itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String key = itemId != null ? itemId.toString() : "";
        String nbt = stableNbtString(stack.getTag());
        return nbt.isEmpty() ? key : key + "|" + nbt;
    }

    /** Produce a deterministic string from a CompoundTag by sorting keys at each level.
     *  Avoids the non-deterministic {@code CompoundTag.toString()} from HashMap iteration. */
    public static String stableNbtString(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return "";
        var keys = new ArrayList<>(tag.getAllKeys());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(key).append('=');
            Tag val = tag.get(key);
            if (val instanceof CompoundTag child) {
                sb.append('{').append(stableNbtString(child)).append('}');
            } else if (val instanceof ListTag list) {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(',');
                    Tag elem = list.get(i);
                    if (elem instanceof CompoundTag c) {
                        sb.append('{').append(stableNbtString(c)).append('}');
                    } else {
                        sb.append(elem);
                    }
                }
                sb.append(']');
            } else {
                sb.append(val);
            }
        }
        return sb.toString();
    }
}
