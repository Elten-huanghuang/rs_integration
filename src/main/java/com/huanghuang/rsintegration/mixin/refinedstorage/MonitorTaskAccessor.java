package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingRequestInfo;
import com.refinedmods.refinedstorage.screen.CraftingMonitorScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.UUID;

@Mixin(value = CraftingMonitorScreen.Task.class, remap = false)
public interface MonitorTaskAccessor {
    @Accessor("id") UUID rsi$id();
    @Accessor("requested") ICraftingRequestInfo rsi$requested();
    @Accessor("qty") int rsi$quantity();
    @Accessor("completionPercentage") int rsi$completionPercentage();
}
