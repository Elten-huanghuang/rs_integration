package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.reflection.probes.GoetyReflection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public final class GoetyGuiClientEventHandler {

    private GoetyGuiClientEventHandler() {}

    @SubscribeEvent
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        try {
            Class<?> registrar = Class.forName(
                    "com.huanghuang.antientropycore.module.goety.GoetyGuiRecipeRegistrar");
            registrar.getMethod("tryRegister").invoke(null);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
    }

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

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        try {
            Class<?> cache = Class.forName(
                    "com.huanghuang.antientropycore.module.goety.GoetyClientRitualCache");
            cache.getMethod("clear").invoke(null);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (event.getScreen().getClass().getName()
                .equals("com.huanghuang.antientropycore.module.goety.GoetyRitualScreen")) {
            clearIpnWidgets();
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearIpnWidgets() {
        try {
            Class<?> cls = Class.forName("org.anti_ad.mc.ipnext.gui.inject.ContainerScreenEventHandler");
            Field f = cls.getDeclaredField("INSTANCE");
            f.setAccessible(true);
            Object instance = f.get(null);
            Field wf = cls.getDeclaredField("currentWidgets");
            wf.setAccessible(true);
            wf.set(instance, new ArrayList<>());
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        try {
            Class<?> cls = Class.forName("org.anti_ad.mc.ipnext.gui.inject.InsertWidgetHandler");
            Field f = cls.getDeclaredField("INSTANCE");
            f.setAccessible(true);
            Object instance = f.get(null);
            Field wf = cls.getDeclaredField("currentWidgets");
            wf.setAccessible(true);
            wf.set(instance, new ArrayList<>());
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
    }
}
