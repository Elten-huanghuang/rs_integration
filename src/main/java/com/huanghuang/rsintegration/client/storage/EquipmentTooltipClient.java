package com.huanghuang.rsintegration.client.storage;

import com.huanghuang.rsintegration.util.EquipmentTooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** 在已有提示生成后补上主题和展开说明，保留可选模组的原始提示逻辑。 */
@OnlyIn(Dist.CLIENT)
public final class EquipmentTooltipClient {
    private EquipmentTooltipClient() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().isEmpty()) return;
        var id = ForgeRegistries.ITEMS.getKey(event.getItemStack().getItem());
        var kind = EquipmentTooltip.kindFor(id);
        if (kind != null) {
            EquipmentTooltip.decorate(event.getToolTip(), kind, Screen.hasShiftDown());
        }
        EquipmentTooltip.tintMetadata(event.getToolTip(), id);
    }
}
