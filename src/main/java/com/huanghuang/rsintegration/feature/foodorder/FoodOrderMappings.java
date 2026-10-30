package com.huanghuang.rsintegration.feature.foodorder;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.MissingMappingsEvent;

/**
 * 旧物品 ID 兼容。
 *
 * <p>旧附属的点餐终端注册为 {@code rsfoodorder:order_terminal}，合并后改为
 * {@code rs_integration:order_terminal}。这里在存档加载时把旧 ID 重映射到新物品，
 * 避免玩家背包/箱子里的终端变成空气。</p>
 */
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FoodOrderMappings {

    private static final String LEGACY_MOD_ID = "rsfoodorder";
    private static final String TERMINAL_PATH = "order_terminal";

    private FoodOrderMappings() {}

    @SubscribeEvent
    public static void onMissingMappings(MissingMappingsEvent event) {
        for (MissingMappingsEvent.Mapping<Item> mapping
                : event.getMappings(ForgeRegistries.Keys.ITEMS, LEGACY_MOD_ID)) {
            if (TERMINAL_PATH.equals(mapping.getKey().getPath())) {
                mapping.remap(ModItems.ORDER_TERMINAL.get());
            }
        }
    }
}
