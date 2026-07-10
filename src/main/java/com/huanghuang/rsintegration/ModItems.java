package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.resonance.backpack.ResonanceBackpackContainer;
import com.huanghuang.rsintegration.resonance.item.ResonanceDiskItem;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {

    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, RSIntegrationMod.MOD_ID);

    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, RSIntegrationMod.MOD_ID);

    public static final RegistryObject<ResonanceDiskItem> RESONANCE_STORAGE_DISK =
            ITEMS.register("resonance_storage_disk", () -> ResonanceDiskItem.INSTANCE);

    public static final RegistryObject<MenuType<ResonanceBackpackContainer>> RESONANCE_BACKPACK =
            MENUS.register("resonance_backpack",
                    () -> IForgeMenuType.create(ResonanceBackpackContainer::new));

    private ModItems() {}

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        MENUS.register(modBus);
    }
}
