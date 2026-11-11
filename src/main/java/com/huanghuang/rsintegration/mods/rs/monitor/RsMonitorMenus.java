package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.container.CraftingMonitorContainerMenu;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.blockentity.grid.GridBlockEntity;
import com.refinedmods.refinedstorage.screen.EmptyScreenInfoProvider;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 增加进入 RS 原生监控器的菜单工厂，不要求额外的监控器方块或物品。 */
public final class RsMonitorMenus {
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, RSIntegrationMod.MOD_ID);
    public static final RegistryObject<MenuType<CraftingMonitorContainerMenu>> NETWORK_MONITOR = MENUS.register(
            "rs_network_monitor", () -> IForgeMenuType.create(RsMonitorMenus::createClient));
    public static final RegistryObject<MenuType<GridContainerMenu>> REMOTE_CRAFTING_GRID = MENUS.register(
            "rs_remote_crafting_grid", () -> IForgeMenuType.create((id, inventory, data) ->
                    new RemoteCraftingGridMenu(new RemoteCraftingGridView(), null, inventory.player, id, data.readGlobalPos())));

    private RsMonitorMenus() {}

    public static void register(IEventBus bus) { MENUS.register(bus); }

    private static CraftingMonitorContainerMenu createClient(int id, Inventory inventory, FriendlyByteBuf data) {
        return new NetworkCraftingMonitorMenu(NETWORK_MONITOR.get(), new NetworkCraftingMonitor(
                selected -> BatchCraftNetworkHandler.CHANNEL.sendToServer(
                        new RsNetworkMonitorSelectPacket(id, selected.orElse(null)))), inventory.player, id);
    }

    static void open(ServerPlayer player, INetwork network) {
        var monitor = new NetworkCraftingMonitor(network, player);
        NetworkHooks.openScreen(player, new SimpleMenuProvider((id, inventory, viewer) ->
                new NetworkCraftingMonitorMenu(NETWORK_MONITOR.get(), monitor, viewer, id), monitor.getTitle()));
    }

    static void openTerminal(ServerPlayer player, GridBlockEntity block) {
        GlobalPos terminal = GlobalPos.of(block.getLevel().dimension(), block.getBlockPos());
        NetworkHooks.openScreen(player, new SimpleMenuProvider((id, inventory, viewer) -> {
            var menu = new RemoteCraftingGridMenu(block.getNode(), block, viewer, id, terminal);
            // 与 RS GridMenuProvider 一致：服务端先初始化槽位，客户端交给 GridScreen 初始化。
            menu.setScreenInfoProvider(new EmptyScreenInfoProvider());
            menu.initSlots();
            return menu;
        }, block.getNode().getTitle()),
                data -> data.writeGlobalPos(terminal));
    }
}
