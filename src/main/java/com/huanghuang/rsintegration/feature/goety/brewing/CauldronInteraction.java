package com.huanghuang.rsintegration.feature.goety.brewing;

import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import com.huanghuang.rsintegration.feature.goety.menu.WitchCauldronMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;

public final class CauldronInteraction {
    private CauldronInteraction() {}
    @SubscribeEvent
    public static void recipesReloaded(net.minecraftforge.event.OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;
        // Do not keep a menu's catalogue or outstanding plans across a datapack reload.
        for (var player : event.getPlayerList().getPlayers()) if (player.containerMenu instanceof WitchCauldronMenu) {
            player.closeContainer(); player.displayClientMessage(WitchCauldronMenu.text("recipes_changed"), true);
        }
    }
    public static void open(ServerPlayer player, BrewCauldronBlockEntity be, boolean remote) {
        if (!com.huanghuang.rsintegration.config.RSIntegrationConfig.ENABLE_GOETY.get()) return;
        net.minecraftforge.network.NetworkHooks.openScreen(player,
                new SimpleMenuProvider((id, inv, ignored) -> WitchCauldronMenu.server(id, inv, be, remote),
                        Component.translatable("container.rs_integration.witch_cauldron")),
                data -> data.writeBlockPos(be.getBlockPos()));
        if (player.containerMenu instanceof WitchCauldronMenu menu) menu.sync(player);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!com.huanghuang.rsintegration.config.RSIntegrationConfig.ENABLE_GOETY.get()) return;
        if (event.isCanceled() || event.getUseBlock() == Event.Result.DENY) return;
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof BrewCauldronBlockEntity be)) return;
        var player = event.getEntity();
        // Sneaking remains RSI's binding gesture. All ordinary items keep Goety's native use.
        if (player.isShiftKeyDown()) return;
        boolean terminal = isTerminal(event.getItemStack())
                || event.getHand() == InteractionHand.MAIN_HAND && player.getMainHandItem().isEmpty()
                && isTerminal(player.getOffhandItem());
        if (!terminal) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        if (!(player instanceof ServerPlayer server) || player.isSpectator()
                || !event.getLevel().mayInteract(player, event.getPos())) return;
        open(server, be, false);
    }

    private static boolean isTerminal(net.minecraft.world.item.ItemStack stack) {
        // Keep this feature loadable in Goety + BD installations without RS classes.
        if (!net.minecraftforge.fml.ModList.get().isLoaded("refinedstorage")) return false;
        for (Class<?> type = stack.getItem().getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("com.refinedmods.refinedstorage.item.NetworkItem")) return true;
        }
        return false;
    }
}
