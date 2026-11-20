package com.huanghuang.rsintegration.mixin.alexsmobs;

import com.huanghuang.rsintegration.feature.transmutation.AlexsMobsTransmutation;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

@Pseudo
@Mixin(targets = "com.github.alexthe666.alexsmobs.inventory.MenuTransmutationTable", remap = false)
public abstract class TransmutationTableMenuMixin extends AbstractContainerMenu
        implements TransmutationTableMenuAccess {
    @Shadow(remap = false)
    public abstract void transmute(Player player, int choice);

    @Unique
    private BlockEntity rsIntegration$table;

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
                                        Player player, @Coerce Object table,
                                        CallbackInfo callback) {
        rsIntegration$table = (BlockEntity) table;
        rsIntegration$rerollState = addDataSlot(DataSlot.standalone());
    }

    // 可选目标不参与编译期名称映射，分别指定开发和发行环境的方法名。
    @Inject(method = {"clickMenuButton(Lnet/minecraft/world/entity/player/Player;I)Z",
                      "m_6366_(Lnet/minecraft/world/entity/player/Player;I)Z"},
            at = @At("HEAD"), cancellable = true, remap = false)
    private void rsIntegration$autoTransmute(Player player, int button,
                                            CallbackInfoReturnable<Boolean> callback) {
        if (button != TransmutationTableReroll.BUTTON_ID) return;
        callback.setReturnValue(true);
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (serverPlayer.containerMenu != (Object) this
                || !(rsIntegration$table instanceof TransmutationTableStateAccess table)
                || rsIntegration$table.isRemoved() || rsIntegration$table.getLevel() != player.level()
                || !stillValid(player)) {
            rsIntegration$finish(Outcome.UNAVAILABLE);
            return;
        }
        if (rsIntegration$awaitingRoll) return;
        UUID pending = table.rsIntegration$getRerollPlayer();
        if (pending != null) {
            if (pending.equals(player.getUUID())) rsIntegration$awaitingRoll = true;
            else rsIntegration$finish(Outcome.UNAVAILABLE);
            return;
        }
        List<ItemStack> possibilities = rsIntegration$possibilities(table);
        Outcome outcome = rsIntegration$evaluate(serverPlayer, possibilities);
        if (outcome != Outcome.READY) {
            // 原模组重新打开已有候选的台子时不会主动同步，停止前补齐当前显示。
            AlexsMobsTransmutation.syncPossibilities(serverPlayer, possibilities);
            rsIntegration$finish(outcome);
            return;
        }
        rsIntegration$awaitingRoll = true;
        transmute(player, 0);
        // 其他模组可能取消原生嬗变，此时不能等一个永远不会到达的抽取回调。
        if (!player.getUUID().equals(table.rsIntegration$getRerollPlayer())) {
            rsIntegration$finish(Outcome.UNAVAILABLE);
        }
    }

    @Override
    public int rsIntegration$getRerollState() {
        return rsIntegration$rerollState.get();
    }

    @Override
    public void rsIntegration$onResultsChanged(Player player, BlockEntity source) {
        if (source != rsIntegration$table || !rsIntegration$awaitingRoll
                || !(source instanceof TransmutationTableStateAccess table)
                || !(player instanceof ServerPlayer serverPlayer)
                || serverPlayer.containerMenu != (Object) this) return;
        rsIntegration$finish(rsIntegration$evaluate(serverPlayer, rsIntegration$possibilities(table)));
    }

    @Unique
    private List<ItemStack> rsIntegration$possibilities(TransmutationTableStateAccess table) {
        return List.of(table.rsIntegration$getPossibility(0), table.rsIntegration$getPossibility(1),
                table.rsIntegration$getPossibility(2));
    }

    @Unique
    private Outcome rsIntegration$evaluate(ServerPlayer player, List<ItemStack> possibilities) {
        int experienceCost = AlexsMobsTransmutation.experienceCost();
        if (experienceCost < 0) return Outcome.UNAVAILABLE;
        return TransmutationTableReroll.evaluate(getSlot(0).getItem(), possibilities,
                player.experienceLevel, experienceCost,
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
