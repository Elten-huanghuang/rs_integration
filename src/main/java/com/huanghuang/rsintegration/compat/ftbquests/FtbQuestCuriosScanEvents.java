package com.huanghuang.rsintegration.compat.ftbquests;

import java.lang.reflect.InvocationTargetException;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 让 Curios 槽位中的物品也能触发 FTB 物品任务重扫。 */
public final class FtbQuestCuriosScanEvents {

    private static final String CURIO_CHANGE_EVENT =
            "top.theillusivec4.curios.api.event.CurioChangeEvent";

    private FtbQuestCuriosScanEvents() {
    }

    @SubscribeEvent
    public static void onEvent(Event event) {
        if (!CURIO_CHANGE_EVENT.equals(event.getClass().getName())
                || !(event instanceof LivingEvent livingEvent)
                || !(livingEvent.getEntity() instanceof ServerPlayer player)) return;
        try {
            Object to = event.getClass().getMethod("getTo").invoke(event);
            if (to instanceof ItemStack stack && !stack.isEmpty()) {
                StorageQuestScanService.schedulePlayerItemScan(player);
            }
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException ignored) {
            // Curios API is optional; an incompatible event must not affect FTB quests.
        }
    }
}
