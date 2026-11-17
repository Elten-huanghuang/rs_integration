package com.huanghuang.rsintegration.mixin.alexsmobs;

import com.github.alexthe666.alexsmobs.AlexsMobs;
import com.github.alexthe666.alexsmobs.config.AMConfig;
import com.github.alexthe666.alexsmobs.inventory.MenuTransmutationTable;
import com.github.alexthe666.alexsmobs.message.MessageUpdateTransmutablesToDisplay;
import com.github.alexthe666.alexsmobs.tileentity.TileEntityTransmutationTable;
import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableMenuAccess;
import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableReroll;
import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableReroll.Outcome;
import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableStateAccess;
import com.huanghuang.rsintegration.feature.villager.tradelock.VillagerTradeLockService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

@Mixin(MenuTransmutationTable.class)
public abstract class TransmutationTableMenuMixin extends AbstractContainerMenu
        implements TransmutationTableMenuAccess {
    @Shadow(remap = false)
    private TileEntityTransmutationTable table;

    @Shadow(remap = false)
    public abstract void transmute(Player player, int choice);

    @Unique
    private DataSlot rsIntegration$rerollState;

    @Unique
    private boolean rsIntegration$awaitingRoll;

    protected TransmutationTableMenuMixin(MenuType<?> type, int containerId) {
        super(type, containerId);
    }

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;"
            + "Lnet/minecraft/world/inventory/ContainerLevelAccess;"
            + "Lnet/minecraft/world/entity/player/Player;"
            + "Lcom/github/alexthe666/alexsmobs/tileentity/TileEntityTransmutationTable;)V",
            at = @At("RETURN"), remap = false)
    private void rsIntegration$initState(int id, Inventory inventory, ContainerLevelAccess access,
                                        Player player, TileEntityTransmutationTable table,
                                        CallbackInfo callback) {
        rsIntegration$rerollState = addDataSlot(DataSlot.standalone());
    }

    @Inject(method = "clickMenuButton", at = @At("HEAD"), cancellable = true)
    private void rsIntegration$autoTransmute(Player player, int button,
                                            CallbackInfoReturnable<Boolean> callback) {
        if (button != TransmutationTableReroll.BUTTON_ID) return;
        callback.setReturnValue(true);
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (serverPlayer.containerMenu != (Object) this || table == null || table.isRemoved()
                || table.getLevel() != player.level() || !stillValid(player)) {
            rsIntegration$finish(Outcome.UNAVAILABLE);
            return;
        }
        if (rsIntegration$awaitingRoll) return;
        UUID pending = ((TransmutationTableStateAccess) table).rsIntegration$getRerollPlayer();
        if (pending != null) {
            if (pending.equals(player.getUUID())) rsIntegration$awaitingRoll = true;
            else rsIntegration$finish(Outcome.UNAVAILABLE);
            return;
        }
        Outcome outcome = rsIntegration$evaluate(serverPlayer);
        if (outcome != Outcome.READY) {
            // 原模组重新打开已有候选的台子时不会主动同步，停止前补齐当前显示。
            AlexsMobs.NETWORK_WRAPPER.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                    new MessageUpdateTransmutablesToDisplay(player.getId(),
                            table.getPossibility(0), table.getPossibility(1), table.getPossibility(2)));
            rsIntegration$finish(outcome);
            return;
        }
        rsIntegration$awaitingRoll = true;
        transmute(player, 0);
        // 其他模组可能取消原生嬗变，此时不能等一个永远不会到达的抽取回调。
        if (!player.getUUID().equals(
                ((TransmutationTableStateAccess) table).rsIntegration$getRerollPlayer())) {
            rsIntegration$finish(Outcome.UNAVAILABLE);
        }
    }

    @Override
    public int rsIntegration$getRerollState() {
        return rsIntegration$rerollState.get();
    }

    @Override
    public void rsIntegration$onResultsChanged(Player player, BlockEntity source) {
        if (source != table || !rsIntegration$awaitingRoll
                || !(player instanceof ServerPlayer serverPlayer)
                || serverPlayer.containerMenu != (Object) this) return;
        rsIntegration$finish(rsIntegration$evaluate(serverPlayer));
    }

    @Unique
    private Outcome rsIntegration$evaluate(ServerPlayer player) {
        return TransmutationTableReroll.evaluate(getSlot(0).getItem(),
                List.of(table.getPossibility(0), table.getPossibility(1), table.getPossibility(2)),
                player.experienceLevel, AMConfig.transmutingExperienceCost,
                player.getAbilities().instabuild,
                stack -> VillagerTradeLockService.isBookmarked(player, stack));
    }

    @Unique
    private void rsIntegration$finish(Outcome outcome) {
        rsIntegration$awaitingRoll = false;
        rsIntegration$rerollState.set(TransmutationTableReroll.nextState(
                rsIntegration$rerollState.get(), outcome));
        broadcastChanges();
    }
}
