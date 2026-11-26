package com.huanghuang.rsintegration.storage.disk.rs;

import com.refinedmods.refinedstorage.api.storage.StorageType;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskProvider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** 专用盘共用一份资源记录，物品预设限定允许写入的资源。 */
public final class InfiniteDiskItem extends Item implements IStorageDiskProvider {
    private static final String RECORD = "InfiniteResource";
    private static final String LEGACY_FLUID = "Fluid";
    private final StorageType type;
    private final String recordType;
    private final ResourceLocation preset;

    public InfiniteDiskItem(StorageType type, ResourceLocation preset) {
        super(new Properties().stacksTo(1));
        this.type = type;
        this.recordType = switch (type) {
            case ITEM -> "item";
            case FLUID -> "fluid";
        };
        this.preset = preset;
    }

    @Nullable ResourceLocation resource(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        // 原先无记录的水盘继续使用预设；显式记录与预设不符时停用供给。
        if (tag == null) return preset;
        if (tag.contains(RECORD)) {
            if (!tag.contains(RECORD, Tag.TAG_COMPOUND)) return null;
            CompoundTag record = tag.getCompound(RECORD);
            return recordType.equals(record.getString("type")) && preset.toString().equals(record.getString("id"))
                    ? preset : null;
        }
        if (tag.contains(LEGACY_FLUID)
                && (type != StorageType.FLUID || !preset.toString().equals(tag.getString(LEGACY_FLUID)))) return null;
        return preset;
    }

    void writeRecord(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        if (tag.contains(RECORD)) return;
        CompoundTag record = new CompoundTag();
        record.putString("type", recordType);
        record.putString("id", preset.toString());
        tag.put(RECORD, record);
    }

    @Override public StorageType getType() { return type; }
    @Override public int getCapacity(ItemStack stack) { return 0; }
    @Override public boolean isValid(ItemStack stack) { return resource(stack) != null; }

    // 虚拟盘由物理槽位直接挂载，RS 磁盘管理器不为它分配库存 UUID。
    @Override @Nullable public UUID getId(ItemStack stack) { return null; }
    @Override public void setId(ItemStack stack, UUID id) {}

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(getDescriptionId() + ".tooltip"));
    }
}
