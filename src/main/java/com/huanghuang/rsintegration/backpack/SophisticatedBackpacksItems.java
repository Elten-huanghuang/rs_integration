package com.huanghuang.rsintegration.backpack;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryObject;
import net.p3pp3rf1y.sophisticatedbackpacks.Config;
import net.p3pp3rf1y.sophisticatedbackpacks.upgrades.refill.RefillUpgradeContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.upgrades.refill.RefillUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.client.gui.StorageScreenBase;
import net.p3pp3rf1y.sophisticatedcore.client.gui.UpgradeGuiManager;
import net.p3pp3rf1y.sophisticatedcore.client.gui.utils.Position;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerRegistry;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerType;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilteredUpgradeContainer;
import net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeContainer;
import net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeContainer;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.pickup.PickupUpgradeWrapper;

import java.util.Objects;

public final class SophisticatedBackpacksItems {

    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, RSIntegrationMod.MOD_ID);

    public static final RegistryObject<RSPickupUpgradeItem> RS_PICKUP_UPGRADE;
    public static final RegistryObject<MagnetUpgradeItem> RS_MAGNET_UPGRADE;
    public static final RegistryObject<RSRefillUpgradeItem> RS_REFILL_UPGRADE;
    public static final RegistryObject<RSFeedingUpgradeItem> RS_FEEDING_UPGRADE;

    private static final UpgradeContainerType<PickupUpgradeWrapper,
            ContentsFilteredUpgradeContainer<PickupUpgradeWrapper>> RS_PICKUP_TYPE;
    private static final UpgradeContainerType<MagnetUpgradeWrapper, MagnetUpgradeContainer> RS_MAGNET_TYPE;
    private static final UpgradeContainerType<RefillUpgradeWrapper, RefillUpgradeContainer> RS_REFILL_TYPE;
    private static final UpgradeContainerType<FeedingUpgradeWrapper, FeedingUpgradeContainer> RS_FEEDING_TYPE;

    static {
        RS_PICKUP_UPGRADE = ITEMS.register("rs_pickup_upgrade", () -> {
            var config = Config.SERVER.pickupUpgrade;
            return new RSPickupUpgradeItem(
                    Objects.requireNonNull(config.filterSlots)::get,
                    Config.SERVER.maxUpgradesPerStorage);
        });

        RS_MAGNET_UPGRADE = ITEMS.register("rs_magnet_upgrade", () -> {
            var config = Config.SERVER.advancedMagnetUpgrade;
            return new RSMagnetUpgradeItem(
                    Objects.requireNonNull(config.magnetRange)::get,
                    Objects.requireNonNull(config.filterSlots)::get,
                    Config.SERVER.maxUpgradesPerStorage);
        });

        RS_REFILL_UPGRADE = ITEMS.register("rs_refill_upgrade", () -> {
            var config = Config.SERVER.advancedRefillUpgrade;
            return new RSRefillUpgradeItem(
                    Objects.requireNonNull(config.filterSlots)::get);
        });

        RS_FEEDING_UPGRADE = ITEMS.register("rs_feeding_upgrade", () -> {
            var config = Config.SERVER.advancedFeedingUpgrade;
            return new RSFeedingUpgradeItem(
                    Objects.requireNonNull(config.filterSlots)::get,
                    Config.SERVER.maxUpgradesPerStorage);
        });

        RS_PICKUP_TYPE = new UpgradeContainerType<>(
                (Player player, int containerId, PickupUpgradeWrapper wrapper,
                 UpgradeContainerType<PickupUpgradeWrapper, ContentsFilteredUpgradeContainer<PickupUpgradeWrapper>> type) ->
                        new ContentsFilteredUpgradeContainer<>(player, containerId, wrapper, type));

        RS_MAGNET_TYPE = new UpgradeContainerType<>(
                (Player player, int containerId, MagnetUpgradeWrapper wrapper,
                 UpgradeContainerType<MagnetUpgradeWrapper, MagnetUpgradeContainer> type) ->
                        new MagnetUpgradeContainer(player, containerId, wrapper, type));

        RS_REFILL_TYPE = new UpgradeContainerType<>(
                (Player player, int containerId, RefillUpgradeWrapper wrapper,
                 UpgradeContainerType<RefillUpgradeWrapper, RefillUpgradeContainer> type) ->
                        new RefillUpgradeContainer(player, containerId, wrapper, type));

        RS_FEEDING_TYPE = new UpgradeContainerType<>(
                (Player player, int containerId, FeedingUpgradeWrapper wrapper,
                 UpgradeContainerType<FeedingUpgradeWrapper, FeedingUpgradeContainer> type) ->
                        new FeedingUpgradeContainer(player, containerId, wrapper, type));
    }

    private SophisticatedBackpacksItems() {}

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(SophisticatedBackpacksItems::registerContainers);
    }

    private static void registerContainers(RegisterEvent event) {
        if (!event.getRegistryKey().equals(ForgeRegistries.Keys.MENU_TYPES)) return;

        UpgradeContainerRegistry.register(
                RS_PICKUP_UPGRADE.getId(), RS_PICKUP_TYPE);
        UpgradeContainerRegistry.register(
                RS_MAGNET_UPGRADE.getId(), RS_MAGNET_TYPE);
        UpgradeContainerRegistry.register(
                RS_REFILL_UPGRADE.getId(), RS_REFILL_TYPE);
        UpgradeContainerRegistry.register(
                RS_FEEDING_UPGRADE.getId(), RS_FEEDING_TYPE);

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> SophisticatedBackpacksItems::registerClientTabs);
    }

    @SuppressWarnings("unchecked")
    private static void registerClientTabs() {
        UpgradeGuiManager.registerTab(RS_PICKUP_TYPE,
                (ContentsFilteredUpgradeContainer<PickupUpgradeWrapper> container,
                 Position position, StorageScreenBase<?> screen) ->
                        new RSPickupUpgradeTab(container, position, screen, 4,
                                net.p3pp3rf1y.sophisticatedbackpacks.client.gui.SBPButtonDefinitions.BACKPACK_CONTENTS_FILTER_TYPE));

        UpgradeGuiManager.registerTab(RS_MAGNET_TYPE,
                (MagnetUpgradeContainer container, Position position,
                 StorageScreenBase<?> screen) ->
                        new RSMagnetUpgradeTab(container, position, screen, 4,
                                net.p3pp3rf1y.sophisticatedbackpacks.client.gui.SBPButtonDefinitions.BACKPACK_CONTENTS_FILTER_TYPE));

        UpgradeGuiManager.registerTab(RS_REFILL_TYPE,
                (RefillUpgradeContainer container, Position position,
                 StorageScreenBase<?> screen) ->
                        new RSRefillUpgradeTab(container, position, screen,
                                Config.SERVER.advancedRefillUpgrade.slotsInRow.get()));

        UpgradeGuiManager.registerTab(RS_FEEDING_TYPE,
                (FeedingUpgradeContainer container, Position position,
                 StorageScreenBase<?> screen) ->
                        new RSFeedingUpgradeTab(container, position, screen,
                                Config.SERVER.advancedFeedingUpgrade.slotsInRow.get()));
    }
}
