package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.util.TextBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public final class BindingTooltipHandler {

    private BindingTooltipHandler() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        List<BindingStorage.BindingEntry> bindings = BindingStorage.getBindings(event.getItemStack());
        if (bindings.isEmpty()) return;

        if (Screen.hasShiftDown()) {
            event.getToolTip().add(
                    TextBuilder.translate("gui.rs_integration.altar.bound_item_header")
                            .colorFlow(0x6495ED, 0x87CEEB, 0x6495ED)
                            .build());
            for (BindingStorage.BindingEntry entry : bindings) {
                Component blockName = BindingEventHandler.resolveBlockName(
                        entry.blockKey(), entry.blockRegKey(), entry.displayStack());
                String dimName = dimDisplayName(entry.dim());
                event.getToolTip().add(
                        TextBuilder.of("  ")
                                .append(TextBuilder.of(blockName.getString())
                                        .colorFlow(0xFFD700, 0xFFB800, 0xFF8C00, 0xFFB800)
                                        .build())
                                .append(TextBuilder.of(" @ ").gray().build())
                                .append(TextBuilder.of(dimName).cornflowerBlue().build())
                                .append(TextBuilder.of(" " + entry.pos().toShortString())
                                        .darkAqua().build())
                                .build());
            }
        } else {
            event.getToolTip().add(
                    TextBuilder.translate("gui.rs_integration.altar.shift_to_view")
                            .gray()
                            .build());
        }
    }

    private static String dimDisplayName(ResourceLocation dim) {
        if (dim == null) return "?";
        String key = "dimension." + dim.getNamespace() + "." + dim.getPath();
        if (I18n.exists(key)) return I18n.get(key);
        return formatPath(dim.getPath());
    }

    private static String formatPath(String path) {
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }
}
