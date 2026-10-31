package com.huanghuang.rsintegration.feature.goety.test;

import com.huanghuang.rsintegration.feature.goety.GoetyCauldronFeature;
import com.huanghuang.rsintegration.feature.goety.integration.MissingMaterialOrders;
import com.huanghuang.rsintegration.feature.goety.menu.WitchCauldronMenu;
import com.huanghuang.rsintegration.api.consumer.*;
import com.huanghuang.rsintegration.api.storage.RSIStorageApi;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import java.util.*;

/** Uses an actual connected integrated-server player; proves recursive successful delivery. */
@Mod.EventBusSubscriber(modid="rs_integration", value=Dist.CLIENT)
public final class OnlineCraftSmoke {
    private static UUID playerId;
    private static net.minecraft.server.MinecraftServer server;
    private static MissingMaterialOrders orders;
    private static int ticks;
    private static boolean started;
    private static com.refinedmods.refinedstorage.api.network.INetwork network;
    public static void begin(net.minecraft.server.MinecraftServer runningServer, UUID id) {
        runningServer.execute(() -> {
            server = runningServer; playerId = id; ticks = 0;
            var level = server.overworld(); var pos = new BlockPos(8,101,0);
            level.removeBlock(pos, false); level.removeBlock(pos.east(), false);
            level.setBlockAndUpdate(pos, com.refinedmods.refinedstorage.RSBlocks.CREATIVE_CONTROLLER
                    .get(com.refinedmods.refinedstorage.util.ColorMap.DEFAULT_COLOR).get().defaultBlockState());
            level.setBlockAndUpdate(pos.east(), com.refinedmods.refinedstorage.RSBlocks.STORAGE_BLOCKS
                    .get(com.refinedmods.refinedstorage.apiimpl.storage.ItemStorageType.ONE_K).get().defaultBlockState());
        });
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) return;
        try {
            var player = server.getPlayerList().getPlayer(playerId);
            if (player == null) throw new AssertionError("Test player disconnected");
            if (++ticks > 1200) throw new AssertionError("Recursive delivery timed out");
            if (ticks < 30 || ticks % 5 != 0) return;
            if (!started) {
                if (RSICraftConsumerApi.get().orElseThrow().state() != ConsumerServiceState.READY) return;
                var pos = new BlockPos(8,101,0);
                var controller = (com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity)player.serverLevel().getBlockEntity(pos);
                network = controller.getNetwork();
                network.getNodeGraph().invalidate(com.refinedmods.refinedstorage.api.util.Action.PERFORM, player.serverLevel(), pos);
                com.huanghuang.rsintegration.network.RSIntegrationNetwork.rememberActiveSidePanelNetwork(playerId, network);
                var session = new com.huanghuang.rsintegration.storage.rs.RefinedStorageBackend().openSession(network);
                if (!session.insert(player,new ItemStack(Items.OAK_LOG,2),false).complete()) throw new AssertionError("Insert logs failed");
                player.getInventory().clearContent();
                orders = new MissingMaterialOrders();
                var link = RSIStorageApi.get().orElseThrow().defaultStorage(player).orElseThrow();
                var message = orders.start(player, List.of(new ItemStack(Items.STICK,4)),link);
                if (!orders.busy()) throw new AssertionError("Order not started: " + message.getString());
                started = true;
                return;
            }
            orders.tick(player);
            if (!orders.busy()) {
                var message = orders.takeMessage();
                int sticks = WitchCauldronMenu.inventoryCount(player, new ItemStack(Items.STICK));
                if (sticks != 4) throw new AssertionError("Expected 4 sticks, got " + sticks + ": " + (message==null ? "" : message.getString()));
                GoetyCauldronFeature.LOGGER.info("ONLINE_RECURSIVE_DELIVERY_PASS: connected player, actual RS logs -> planks -> 4 sticks through MissingMaterialOrders + public Consumer API");
                finish();
            }
        } catch (Throwable failure) {
            GoetyCauldronFeature.LOGGER.error("ONLINE_RECURSIVE_DELIVERY_FAILED", failure);
            finish();
        }
    }
    private static void finish() {
        try {
            var player=server.getPlayerList().getPlayer(playerId);
            if (orders!=null && player!=null) orders.close(player);
            com.huanghuang.rsintegration.network.RSIntegrationNetwork.forgetActiveSidePanelNetwork(playerId);
            com.huanghuang.rsintegration.network.RSIntegrationNetwork.invalidateNetworkResolution(playerId);
        } finally {
            server=null;
            net.minecraft.client.Minecraft.getInstance().execute(() -> net.minecraft.client.Minecraft.getInstance().stop());
        }
    }
}
