package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.mods.apotheosis.network.ApothSpawnerStatePacket;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import dev.shadowsoffire.apotheosis.spawn.spawner.ApothSpawnerTile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

public final class ApothSpawnerInteractionHandler {
    private ApothSpawnerInteractionHandler() {}

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !event.getEntity().isShiftKeyDown()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack held = event.getItemStack();
        // Apotheosis is also loaded in BD-only profiles.  Resolve the held
        // terminal through the backend-neutral binding registry instead of
        // linking RS NetworkItem into this common event handler.
        if (held.isEmpty() || AltarBindingRegistry.findHook(held).isEmpty()) return;
        // BD reserves crouch-right-click for its native terminal action. Its
        // RSI spawner action is dispatched by the explicit Alt+right-click
        // packet instead (see tryOpenExplicit).
        if (isBeyondDimensionsTerminal(held)) return;
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof ApothSpawnerTile)) return;

        open(player, event.getPos());
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /** Handles the BD terminal's explicit Alt+right-click action. */
    public static boolean tryOpenExplicit(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null || !(player.level().getBlockEntity(pos) instanceof ApothSpawnerTile)) {
            return false;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || !isBeyondDimensionsTerminal(held)) {
            held = player.getOffhandItem();
        }
        if (held.isEmpty() || !isBeyondDimensionsTerminal(held)) return false;
        open(player, pos);
        return true;
    }

    private static void open(ServerPlayer player, BlockPos pos) {
        var snapshot = ApothSpawnerUpgradeService.scan(player,
                player.level().dimension().location(), pos, "");
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                ApothSpawnerStatePacket.from(snapshot));
    }

    private static boolean isBeyondDimensionsTerminal(ItemStack stack) {
        var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && "beyonddimensions".equals(id.getNamespace())
                && "net_terminal_item".equals(id.getPath());
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ApothSpawnerUpgradeService.clearPlayer(player.getUUID());
        ApotheosisLibraryService.clearPlayer(player.getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ApothSpawnerUpgradeService.clearServerState();
        ApotheosisLibraryService.clearServerState();
    }
}
