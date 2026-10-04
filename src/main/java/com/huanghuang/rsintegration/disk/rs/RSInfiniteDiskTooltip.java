package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.disk.UnifiedDiskVisibility;
import com.huanghuang.rsintegration.util.DiskTooltipEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** 为 RS 无限盘提示实际存在的归墟盘转换配方。 */
@OnlyIn(Dist.CLIENT)
public final class RSInfiniteDiskTooltip {
    private static final ResourceLocation INFINITE_ITEM =
            new ResourceLocation("refinedstorage", "creative_storage_disk");
    private static final ResourceLocation INFINITE_FLUID =
            new ResourceLocation("refinedstorage", "creative_fluid_storage_disk");
    private static final ResourceLocation ITEM_RECIPE =
            new ResourceLocation("rs_integration", "unified_storage_disk_from_infinite_item_disk");
    private static final ResourceLocation FLUID_RECIPE =
            new ResourceLocation("rs_integration", "unified_storage_disk_from_infinite_fluid_disk");

    private RSInfiniteDiskTooltip() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!UnifiedDiskVisibility.visible()) return;

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(event.getItemStack().getItem());
        ResourceLocation recipeId = recipeFor(itemId);
        if (recipeId == null || !recipeLoaded(recipeId)) return;

        event.getToolTip().add(DiskTooltipEffects.flow(
                "item.rs_integration.infinite_disk_conversion",
                DiskTooltipEffects.Theme.GUIXU, DiskTooltipEffects.Tone.HINT, 0.25F));
    }

    static ResourceLocation recipeFor(ResourceLocation itemId) {
        if (INFINITE_ITEM.equals(itemId)) return ITEM_RECIPE;
        if (INFINITE_FLUID.equals(itemId)) return FLUID_RECIPE;
        return null;
    }

    private static boolean recipeLoaded(ResourceLocation recipeId) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null
                && minecraft.level.getRecipeManager().byKey(recipeId).isPresent();
    }
}
