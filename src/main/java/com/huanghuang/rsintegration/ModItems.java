package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.storage.disk.UnifiedDiskVisibility;
import com.huanghuang.rsintegration.mods.ironsspellbooks.FluidRefundItem;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.huanghuang.rsintegration.feature.resonance.backpack.ResonanceBackpackContainer;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

public final class ModItems {

    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, RSIntegrationMod.MOD_ID);

    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, RSIntegrationMod.MOD_ID);

    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RSIntegrationMod.MOD_ID);

    /** Optional RS-owned entries are registered only by RSOptionalBootstrap. */
    public static RegistryObject<Item> RESONANCE_STORAGE_DISK;
    public static RegistryObject<Item> DIMENSIONAL_RESONANCE_DISK;
    public static RegistryObject<Item> RS_VOID_UPGRADE;
    public static RegistryObject<Item> UNIFIED_STORAGE_DISK;
    public static RegistryObject<Item> RESONANT_MANA_POOL;
    public static RegistryObject<Item> RESONANT_SOURCE_TANK;
    public static final RegistryObject<Item> ALCHEMIST_INK_FLUID =
            ITEMS.register("alchemist_ink_fluid", InkFluidItem::new);
    public static final RegistryObject<Item> FLUID_REFUND =
            ITEMS.register("fluid_refund", FluidRefundItem::new);
    public static final RegistryObject<MenuType<?>> RESONANCE_BACKPACK = MENUS.register(
            "resonance_backpack",
            () -> IForgeMenuType.create(ResonanceBackpackContainer::new));

    public static final RegistryObject<CreativeModeTab> RSI_TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.rs_integration.main"))
                    .icon(() -> new ItemStack(RESONANCE_STORAGE_DISK == null
                            ? Items.CHEST : RESONANCE_STORAGE_DISK.get()))
                    .displayItems((params, output) -> {
                        if (RESONANCE_STORAGE_DISK != null) {
                            output.accept(RESONANCE_STORAGE_DISK.get());
                        }
                        if (DIMENSIONAL_RESONANCE_DISK != null) {
                            output.accept(DIMENSIONAL_RESONANCE_DISK.get());
                        }
                        if (RESONANT_MANA_POOL != null) {
                            output.accept(RESONANT_MANA_POOL.get());
                        }
                        if (RESONANT_SOURCE_TANK != null) {
                            output.accept(RESONANT_SOURCE_TANK.get());
                        }
                        if (UnifiedDiskVisibility.visible()) output.accept(UNIFIED_STORAGE_DISK.get());
                        // Backpack upgrades are only registered when Sophisticated
                        // Backpacks is present. Look them up by registry name so we
                        // never link SophisticatedBackpacksItems (which would throw
                        // NoClassDefFoundError when SB is absent).
                        acceptIfPresent(output, "rs_pickup_upgrade");
                        acceptIfPresent(output, "rs_magnet_upgrade");
                        acceptIfPresent(output, "rs_refill_upgrade");
                        acceptIfPresent(output, "rs_feeding_upgrade");
                        acceptIfPresent(output, "rs_void_upgrade");
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

    public static void registerOptionalResonance(IEventBus modBus,
                                                  Supplier<? extends Item> diskSupplier) {
        RESONANCE_STORAGE_DISK = ITEMS.register("resonance_storage_disk", diskSupplier);
    }

    public static void registerOptionalVoidUpgrade(IEventBus modBus,
                                                    Supplier<? extends Item> supplier) {
        RS_VOID_UPGRADE = ITEMS.register("rs_void_upgrade", supplier);
    }

    public static void registerOptionalUnifiedDisk(Supplier<? extends Item> supplier) {
        UNIFIED_STORAGE_DISK = ITEMS.register("unified_storage_disk", supplier);
    }

    public static void registerOptionalBeyondDimensions(IEventBus modBus,
                                                         Supplier<? extends Item> diskSupplier) {
        DIMENSIONAL_RESONANCE_DISK = ITEMS.register("resonance_network_generator", diskSupplier);
    }

    public static void registerOptionalMagicBlocks() {
        RESONANT_MANA_POOL = ITEMS.register("resonant_mana_pool",
                () -> new BlockItem(ModBlocks.RESONANT_MANA_POOL.get(), new Item.Properties()));
        RESONANT_SOURCE_TANK = ITEMS.register("resonant_source_tank",
                () -> new BlockItem(ModBlocks.RESONANT_SOURCE_TANK.get(), new Item.Properties()));
    }
}
