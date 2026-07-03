package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.ModClassLoader;
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

    // ── Shared class refs ────────────────────────────────────────
    private static volatile Class<?> darkAltarBlockClass;
    private static volatile Class<?> necroBrazierBlockClass;
    private static volatile Class<?> cursedCageBlockClass;
    private static volatile Class<?> soulCandlestickBlockClass;

    private static void ensureClasses() {
        if (!ModClassLoader.ensureClasses("goety",
                "com.Polarice3.Goety.common.blocks.DarkAltarBlock",
                "com.Polarice3.Goety.common.blocks.NecroBrazierBlock",
                "com.Polarice3.Goety.common.blocks.CursedCageBlock",
                "com.Polarice3.Goety.common.blocks.SoulCandlestickBlock")) return;
        try {
            darkAltarBlockClass = Class.forName(
                    "com.Polarice3.Goety.common.blocks.DarkAltarBlock");
            necroBrazierBlockClass = Class.forName(
                    "com.Polarice3.Goety.common.blocks.NecroBrazierBlock");
            cursedCageBlockClass = Class.forName(
                    "com.Polarice3.Goety.common.blocks.CursedCageBlock");
            soulCandlestickBlockClass = Class.forName(
                    "com.Polarice3.Goety.common.blocks.SoulCandlestickBlock");
        } catch (ClassNotFoundException e) {
            RSIntegrationMod.LOGGER.error("[RSI-Client] Failed to load Goety block classes", e);
        }
    }

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
        ensureClasses();
        if (event.getItemStack().getItem() instanceof BlockItem bi) {
            Block block = bi.getBlock();
            if (darkAltarBlockClass != null && darkAltarBlockClass.isInstance(block)) {
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
        return (necroBrazierBlockClass != null && necroBrazierBlockClass.isInstance(block))
                || (cursedCageBlockClass != null && cursedCageBlockClass.isInstance(block))
                || (soulCandlestickBlockClass != null && soulCandlestickBlockClass.isInstance(block));
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
