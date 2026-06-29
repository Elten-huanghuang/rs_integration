package com.huanghuang.rsintegration.mods.wizards_reborn;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Set;

@OnlyIn(Dist.CLIENT)
public final class WRGuiClientEventHandler {

    private static final Set<String> WR_BLOCK_CLASSES = Set.of(
            "mod.maxbogomol.wizards_reborn.common.block.wissen_crystallizer.WissenCrystallizerBlock",
            "mod.maxbogomol.wizards_reborn.common.block.arcane_iterator.ArcaneIteratorBlock",
            "mod.maxbogomol.wizards_reborn.common.block.arcane_workbench.ArcaneWorkbenchBlock",
            "mod.maxbogomol.wizards_reborn.common.block.crystal.CrystalBlock"
    );

    private WRGuiClientEventHandler() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!RSIntegrationConfig.ENABLE_WIZARDS_REBORN.get()) return;
        if (!net.minecraftforge.fml.ModList.get().isLoaded(ModIds.WIZARDS_REBORN)) return;

        if (event.getItemStack().getItem() instanceof BlockItem bi) {
            String className = bi.getBlock().getClass().getName();
            if (WR_BLOCK_CLASSES.contains(className)) {
                event.getToolTip().add(
                        Component.translatable("gui.rs_integration.wr.binding.rs_bind_hint"));
            }
        }
    }
}
