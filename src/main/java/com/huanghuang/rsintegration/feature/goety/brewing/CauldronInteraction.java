package com.huanghuang.rsintegration.feature.goety.brewing;

import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.feature.goety.integration.GoetyCauldronCompat;
import com.huanghuang.rsintegration.feature.goety.menu.WitchCauldronMenu;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.network.NetworkHooks;

public final class CauldronInteraction {
    private CauldronInteraction() {}
    @SubscribeEvent
    public static void recipesReloaded(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;
        // 配方重载后关闭旧目录，并取消尚未完成的材料订单。
        for (var player : event.getPlayerList().getPlayers()) if (player.containerMenu instanceof WitchCauldronMenu) {
            player.closeContainer(); player.displayClientMessage(WitchCauldronMenu.text("recipes_changed"), true);
        }
    }
    public static void open(ServerPlayer player, BrewCauldronBlockEntity be, boolean remote) {
        if (!RSIntegrationConfig.isGoetyCauldronEnabled()) return;
        if (!CauldronAccess.canOpen(be.mode.name(), GoetyCauldronCompat.hasIngredients(be),
                GoetyCauldronCompat.isBusy(be))) {
            player.displayClientMessage(WitchCauldronMenu.text("cauldron_not_empty"), true);
            return;
        }
        NetworkHooks.openScreen(player,
                new SimpleMenuProvider((id, inv, ignored) -> WitchCauldronMenu.server(id, inv, be, remote),
                        Component.translatable("container.rs_integration.witch_cauldron")),
                data -> data.writeBlockPos(be.getBlockPos()));
        if (player.containerMenu instanceof WitchCauldronMenu menu) menu.sync(player);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!RSIntegrationConfig.isGoetyCauldronEnabled()) return;
        if (event.isCanceled() || event.getUseBlock() == Event.Result.DENY) return;
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof BrewCauldronBlockEntity be)) return;
        var player = event.getEntity();
        // 访问器的普通右键和蹲下右键统一打开编辑器，不进入机器绑定流程。
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

    private static boolean isTerminal(ItemStack stack) {
        return !stack.isEmpty() && AltarBindingRegistry.findHook(stack).isPresent();
    }
}
