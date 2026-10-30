package com.huanghuang.rsintegration.feature.foodorder;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 点餐功能的生命周期事件。 */
public final class FoodOrderEvents {

    private static boolean registered;

    private FoodOrderEvents() {}

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.register(FoodOrderEvents.class);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            FoodOrderService.tick(event.getServer());
            FoodOrderListService.tick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FoodOrderService.clearPlayer(player);
            FoodOrderListService.clearPlayer(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onItemUseFinish(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = event.getItem();
        if (stack.isEmpty() || !stack.isEdible()) {
            return;
        }
        // 未安装 Sol Carrot 时用自带存档记录；安装时两边都记，切模组也不丢进度。
        FoodOrderFoods.markEaten(player, stack);
        FoodOrderListService.clearPlayer(player.getUUID());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // 导入旧附属的点餐进度；没有旧文件时不做任何事。
        FoodOrderEatenData.migrateLegacy(event.getServer());
        FoodOrderListService.invalidate();
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        // 配方索引会在同一批事件里重建，清掉旧列表避免展示过期配方。
        FoodOrderListService.invalidate();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        FoodOrderService.clear();
        FoodOrderListService.invalidate();
    }
}
