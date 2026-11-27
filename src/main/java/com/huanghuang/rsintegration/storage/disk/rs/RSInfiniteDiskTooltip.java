package com.huanghuang.rsintegration.storage.disk.rs;

import com.huanghuang.rsintegration.storage.disk.UnifiedDiskVisibility;
import com.huanghuang.rsintegration.util.DiskTooltipEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;

/** 显示无限水盘和岩浆盘的故事，以及 RS 无限盘的归墟盘转换提示。 */
@OnlyIn(Dist.CLIENT)
public final class RSInfiniteDiskTooltip {
    private static final Map<ResourceLocation, Story> STORIES = Map.of(
            new ResourceLocation("rs_integration", "infinity_water_disk"),
            new Story(10, 0xA7F0EB, 0xD8E9ED, 0xA5CCD4),
            new ResourceLocation("rs_integration", "infinity_lava_disk"),
            new Story(9, 0xFFC389, 0xF0E2DC, 0xDDB3A6));
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
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(event.getItemStack().getItem());
        if (appendStory(itemId, event.getToolTip(), Screen.hasShiftDown())) return;
        if (!UnifiedDiskVisibility.visible()) return;

        ResourceLocation recipeId = recipeFor(itemId);
        if (recipeId == null || !recipeLoaded(recipeId)) return;

        event.getToolTip().add(DiskTooltipEffects.flow(
                "item.rs_integration.infinite_disk_conversion",
                DiskTooltipEffects.Theme.GUIXU, DiskTooltipEffects.Tone.HINT, 0.25F));
    }

    static boolean appendStory(ResourceLocation itemId, List<Component> tooltip, boolean expanded) {
        Story story = itemId == null ? null : STORIES.get(itemId);
        if (story == null) return false;

        String lore = itemId.toLanguageKey("item") + ".lore.";
        if (!expanded) {
            tooltip.add(Component.translatable(lore + "expand")
                    .withStyle(style -> style.withColor(story.hintColor())));
            return true;
        }

        tooltip.add(Component.empty());
        tooltip.add(Component.translatable(lore + "title")
                .withStyle(style -> style.withColor(story.titleColor()).withBold(true)));
        for (int line = 1; line <= story.lines(); line++) {
            tooltip.add(Component.translatable(lore + line)
                    .withStyle(style -> style.withColor(story.bodyColor())));
        }
        return true;
    }

    private record Story(int lines, int titleColor, int bodyColor, int hintColor) {}

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
