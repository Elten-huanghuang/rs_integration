package com.huanghuang.rsintegration.disk;

import com.google.gson.JsonObject;
import com.huanghuang.rsintegration.config.RSStorageConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.common.crafting.conditions.IConditionSerializer;

/** 关闭统一盘时不加载配方，保留物品注册以便旧盘再次开启后恢复。 */
public final class UnifiedDiskEnabledCondition implements ICondition {
    public static final ResourceLocation ID = new ResourceLocation("rs_integration", "unified_disk_enabled");
    @Override public ResourceLocation getID() { return ID; }
    @Override public boolean test(IContext context) { return RSStorageConfig.enabled(RSStorageConfig.UNIFIED_DISK); }

    public static final class Serializer implements IConditionSerializer<UnifiedDiskEnabledCondition> {
        @Override public void write(JsonObject json, UnifiedDiskEnabledCondition value) {}
        @Override public UnifiedDiskEnabledCondition read(JsonObject json) { return new UnifiedDiskEnabledCondition(); }
        @Override public ResourceLocation getID() { return ID; }
    }
}
