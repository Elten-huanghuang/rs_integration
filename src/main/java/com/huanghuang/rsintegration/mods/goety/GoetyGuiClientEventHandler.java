package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.reflection.probes.GoetyReflection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Client-side RS-binding hints for Goety blocks. Adds a tooltip to the Dark Altar
 * and its binding blocks (Necro Brazier / Cursed Cage / Soul Candlestick) telling
 * the player they can be bound to a Refined Storage network.
 */
@OnlyIn(Dist.CLIENT)
public final class GoetyGuiClientEventHandler {

    private GoetyGuiClientEventHandler() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().getItem() instanceof BlockItem bi) {
            Block block = bi.getBlock();
            if (GoetyReflection.darkAltarBlockClass != null && GoetyReflection.darkAltarBlockClass.isInstance(block)) {
                event.getToolTip().add(
                        Component.translatable("gui.rs_integration.altar.tooltip.1"));
                event.getToolTip().add(
                        Component.translatable("gui.rs_integration.altar.tooltip.2"));
            } else if (isGoetyBindingBlock(block)) {
                event.getToolTip().add(
                        Component.translatable("gui.rs_integration.altar.binding.rs_bind_hint"));
            }
        }
    }

    private static boolean isGoetyBindingBlock(Block block) {
        return (GoetyReflection.necroBrazierBlockClass != null && GoetyReflection.necroBrazierBlockClass.isInstance(block))
                || (GoetyReflection.cursedCageBlockClass != null && GoetyReflection.cursedCageBlockClass.isInstance(block))
                || (GoetyReflection.soulCandlestickBlockClass != null && GoetyReflection.soulCandlestickBlockClass.isInstance(block));
    }
}
