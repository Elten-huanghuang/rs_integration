package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.resonance.backpack.ResonanceBackpackContainer;
import com.huanghuang.rsintegration.resonance.item.ResonanceDiskItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RSIntegrationMod.MOD_ID);

    public static final RegistryObject<ResonanceDiskItem> RESONANCE_STORAGE_DISK =
            ITEMS.register("resonance_storage_disk", () -> ResonanceDiskItem.INSTANCE);

    public static final RegistryObject<MenuType<ResonanceBackpackContainer>> RESONANCE_BACKPACK =
            MENUS.register("resonance_backpack",
                    () -> IForgeMenuType.create(ResonanceBackpackContainer::new));

    public static final RegistryObject<CreativeModeTab> RSI_TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.rs_integration.main"))
                    .icon(() -> new ItemStack(RESONANCE_STORAGE_DISK.get()))
                    .displayItems((params, output) -> {
                        output.accept(RESONANCE_STORAGE_DISK.get());
                        // Backpack upgrades are only registered when Sophisticated
                        // Backpacks is present. Look them up by registry name so we
                        // never link SophisticatedBackpacksItems (which would throw
                        // NoClassDefFoundError when SB is absent).
                        acceptIfPresent(output, "rs_pickup_upgrade");
                        acceptIfPresent(output, "rs_magnet_upgrade");
                        acceptIfPresent(output, "rs_refill_upgrade");
                        acceptIfPresent(output, "rs_feeding_upgrade");
                    })
                    .build());

    private ModItems() {}

    private static void acceptIfPresent(CreativeModeTab.Output output, String path) {
        Item item = ForgeRegistries.ITEMS.getValue(
                new ResourceLocation(RSIntegrationMod.MOD_ID, path));
        if (item != null && item != Items.AIR) {
            output.accept(item);
        }
    }

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        MENUS.register(modBus);
        TABS.register(modBus);
    }
}
